"""Patch DirectPackageInstaller's Payload/main.c with two extra modes (selected by the 'url' sent by the app):
  url "/path/x.pkg"  -> install from the PS4's disk with sceAppInstUtilAppInstallPkg(path, 0)
                        (name "COPY|..." => copy the pkg to /user/data/dpi_copy.pkg first, so the original is kept)
  url "@CUSA12345"   -> launch an installed app with sceSystemServiceLaunchApp
Everything else (http urls) goes through the normal BGFT download path."""
import sys

f = sys.argv[1]
s = open(f).read()

inc = "#include <sys/mman.h>"
assert s.count(inc) == 1, "include anchor not found"
decls = r'''
extern int dpi_open(const char*, int, int) __asm__("open");
extern long dpi_read(int, void*, unsigned long) __asm__("read");
extern long dpi_write(int, const void*, unsigned long) __asm__("write");
extern int dpi_close(int) __asm__("close");
#define DPI_O_RDONLY 0
#define DPI_O_WRONLY 1
#define DPI_O_CREAT 0x200
#define DPI_O_TRUNC 0x400
'''
s = s.replace(inc, inc + decls)

helper = r'''static void dpi_note(int(*send)(int, const char*), char* buf, const char* pre, int val) {
	int k = 0;
	while (pre[k]) { buf[k] = pre[k]; k++; }
	buf[k++] = '0'; buf[k++] = 'x';
	for (int i = 7; i >= 0; i--) {
		int d = ((unsigned int)val >> (4 * i)) & 0xF;
		buf[k++] = d < 10 ? ('0' + d) : ('A' + d - 10);
	}
	buf[k] = 0;
	send(222, buf);
}

'''
m = "int main()"
assert s.count(m) == 1, "main anchor not found"
s = s.replace(m, helper + m)

anchor = "int task = BGFT_INVALID_TASK_ID;"
assert s.count(anchor) == 1, "loop anchor not found"
code = r'''if (bgft_params.content_url[0] == '@') {
			void* lss = dlopen("/system/common/lib/libSceSystemService.sprx", 0);
			int(*launch)(const char*, const char**, void*) = lss ? dlsym(lss, "sceSystemServiceLaunchApp") : 0;
			if (launch == 0) {
				sceSysUtilSendSystemNotificationWithText(222, "DPI: launch unavailable (symbol missing)");
				continue;
			}
			char* nb = (char*)mmap(NULL, 0x1000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
			struct { unsigned int sz; int user_id; int app_opt; unsigned long crash_report; unsigned long check_flag; } lp = { .sz = 32, .user_id = uid };
			const char* largv[2] = { 0, 0 };
			rv = launch(bgft_params.content_url + 1, largv, &lp);
			dpi_note(sceSysUtilSendSystemNotificationWithText, nb, "DPI: launch result ", rv);
			continue;
		}

		if (bgft_params.content_url[0] == '/') {
			char* lm = (char*)mmap(NULL, 0x101000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
			const char* path = bgft_params.content_url;
			const char* nm = bgft_params.content_name;
			if (nm[0] == 'C' && nm[1] == 'O' && nm[2] == 'P' && nm[3] == 'Y' && nm[4] == '|') {
				char* buf = lm + 0x1000;
				int fin = dpi_open(path, DPI_O_RDONLY, 0);
				int fout = dpi_open("/user/data/dpi_copy.pkg", DPI_O_WRONLY | DPI_O_CREAT | DPI_O_TRUNC, 0777);
				int ok = (fin >= 0 && fout >= 0);
				long n = 0;
				while (ok && (n = dpi_read(fin, buf, 0x100000)) > 0) {
					char* p = buf;
					while (n > 0) {
						long w = dpi_write(fout, p, n);
						if (w <= 0) { ok = 0; break; }
						p += w; n -= w;
					}
				}
				if (ok && n < 0) ok = 0;
				if (fin >= 0) dpi_close(fin);
				if (fout >= 0) dpi_close(fout);
				if (!ok) {
					sceSysUtilSendSystemNotificationWithText(222, "DPI: local install copy failed (disk full?)");
					continue;
				}
				path = "/user/data/dpi_copy.pkg";
			}
			int(*instPkg)(const char*, int) = dlsym(libSceAppInstUtil, "sceAppInstUtilAppInstallPkg");
			if (instPkg == 0) {
				sceSysUtilSendSystemNotificationWithText(222, "DPI: local install unavailable (symbol missing)");
				continue;
			}
			rv = instPkg(path, 0);
			if (rv) {
				dpi_note(sceSysUtilSendSystemNotificationWithText, lm, "DPI: local install failed ", rv);
			} else {
				sceSysUtilSendSystemNotificationWithText(222, "DPI: local install started");
			}
			continue;
		}

		'''
s = s.replace(anchor, code + anchor)
open(f, 'w').write(s)
print("main.c patched")
