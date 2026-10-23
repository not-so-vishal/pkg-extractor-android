// Port of extractor/main.cpp (shadPS4Plus) with the console I/O replaced by a progress callback.
#include <exception>
#include <filesystem>
#include <functional>
#include <string>
#include <android/log.h>
#define STEP(m) __android_log_print(ANDROID_LOG_ERROR, "pkgextract", "STEP: %s", m)

#ifdef HAVE_EXTRACTOR
#include "common/path_util.h"
#include "common/string_util.h"
#include "core/file_format/pkg.h"
#include "core/file_format/psf.h"
#include "core/loader.h"
#endif

// progress(percent, message) -> return false to cancel
using ProgressFn = std::function<bool(int, const std::string&)>;

#ifdef HAVE_EXTRACTOR
std::string RunExtract(const std::string& pkgPath, const std::string& outDir, const ProgressFn& progress) {
    try {
        std::filesystem::path file = pkgPath;
        std::filesystem::path out = outDir;

        STEP("detect file type");
        if (Loader::DetectFileType(file) != Loader::FileTypes::Pkg) {
            return "Not a valid PKG file";
        }

        STEP("pkg.Open");
        PKG pkg;
        std::string fail;
        if (!pkg.Open(file, fail)) {
            return "Cannot open PKG: " + fail;
        }

        STEP("psf.Open");
        PSF psf;
        if (!psf.Open(pkg.sfo)) {
            return "Could not read SFO";
        }

        out /= pkg.GetTitleID();

        std::string category;
        if (auto c = psf.GetString("CATEGORY"); c.has_value()) {
            category = std::string{*c};
        }
        const std::string pkgType = pkg.GetPkgFlags();

        if (pkgType.contains("PATCH")) {
            progress(0, "Game update");
            out += "-patch";
        } else if (category == "ac") {
            progress(0, "DLC");
            if (auto v = psf.GetString("CONTENT_ID"); v.has_value()) {
                auto parts = Common::SplitString(std::string{*v}, '-');
                if (parts.size() > 2) {
                    out /= parts[2];
                }
            }
        } else {
            progress(0, "Base game");
        }

        STEP("pkg.Extract");
        if (!pkg.Extract(file, out, fail)) {
            return "Cannot extract PKG: " + fail;
        }

        STEP("extract files loop");
        const int n = pkg.GetNumberOfFiles();
        unsigned long long total = 0, done = 0;
        for (int i = 0; i < n; i++) total += pkg.GetFileSize(i);
        for (int i = 0; i < n; i++) {
            const unsigned long long sz = pkg.GetFileSize(i);
            // "F<TAB>name<TAB>size<TAB>index<TAB>count<TAB>doneBytes<TAB>totalBytes"
            const std::string msg = "F\t" + pkg.GetFileName(i) + "\t" + std::to_string(sz) + "\t" +
                                    std::to_string(i + 1) + "\t" + std::to_string(n) + "\t" +
                                    std::to_string(done) + "\t" + std::to_string(total);
            const int pct = total ? static_cast<int>(done * 100 / total) : i * 100 / (n ? n : 1);
            if (!progress(pct, msg)) {
                return "Cancelled";
            }
            pkg.ExtractFiles(i);
            done += sz;
        }
        progress(100, "D\t" + out.string());
        return "";
    } catch (const std::exception& e) {
        return std::string("Exception: ") + e.what();
    } catch (...) {
        return "Unknown error";
    }
}
#else
std::string RunExtract(const std::string&, const std::string&, const ProgressFn&) {
    return "Native extractor not linked. Run setup.sh to copy shadPS4Plus sources, then rebuild.";
}
#endif
