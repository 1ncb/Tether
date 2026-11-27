"""Patch DirectPackageInstaller's Payload/main.c so a content_url that starts with '/'
is installed from the PS4's own disk via sceAppInstUtilAppInstallPkg(path, 0) (flatz's documented PS4 call)."""
import sys

f = sys.argv[1]
s = open(f).read()
anchor = "int task = BGFT_INVALID_TASK_ID;"
assert s.count(anchor) == 1, "anchor not found in main.c"

code = r'''if (bgft_params.content_url[0] == '/') {
			char* lm = (char*)mmap(NULL, 0x4000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
			int(*instPkg)(const char*, int) = dlsym(libSceAppInstUtil, "sceAppInstUtilAppInstallPkg");
			if (instPkg == 0) {
				sceSysUtilSendSystemNotificationWithText(222, "DPI: local install unavailable (symbol missing)");
				continue;
			}
			rv = instPkg(bgft_params.content_url, 0);
			if (rv) {
				const char* pre = "DPI: local install failed 0x";
				int k = 0;
				while (pre[k]) { lm[k] = pre[k]; k++; }
				for (int i = 7; i >= 0; i--) {
					int d = ((unsigned int)rv >> (4 * i)) & 0xF;
					lm[k++] = d < 10 ? ('0' + d) : ('A' + d - 10);
				}
				lm[k] = 0;
				sceSysUtilSendSystemNotificationWithText(222, lm);
			} else {
				sceSysUtilSendSystemNotificationWithText(222, "DPI: local install started");
			}
			continue;
		}

		'''
s = s.replace(anchor, code + anchor)
open(f, 'w').write(s)
print("main.c patched")
