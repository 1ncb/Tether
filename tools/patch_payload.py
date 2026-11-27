"""Patch DirectPackageInstaller's Payload/main.c so a content_url that starts with '/'
is installed from the PS4's own disk via sceAppInstUtilInstallByPackage (no download)."""
import sys

f = sys.argv[1]
s = open(f).read()
anchor = "int task = BGFT_INVALID_TASK_ID;"
assert s.count(anchor) == 1, "anchor not found in main.c"

code = r'''if (bgft_params.content_url[0] == '/') {
			char* lm = (char*)mmap(NULL, 0x8000, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
			const char* meta[6] = { bgft_params.content_url, "", "", bgft_params.id, bgft_params.content_name, "" };
			int(*installByPkg)(const char**, void*, void*) = dlsym(libSceAppInstUtil, "sceAppInstUtilInstallByPackage");
			rv = installByPkg(meta, (void*)lm, (void*)(lm + 0x1000));
			if (rv) {
				char* mb = lm + 0x2000;
				const char* pre = "DPI: local install failed 0x";
				int k = 0;
				while (pre[k]) { mb[k] = pre[k]; k++; }
				for (int i = 7; i >= 0; i--) {
					int d = ((unsigned int)rv >> (4 * i)) & 0xF;
					mb[k++] = d < 10 ? ('0' + d) : ('A' + d - 10);
				}
				mb[k] = 0;
				sceSysUtilSendSystemNotificationWithText(222, mb);
			} else {
				sceSysUtilSendSystemNotificationWithText(222, "DPI: local install started");
			}
			continue;
		}

		'''
s = s.replace(anchor, code + anchor)
open(f, 'w').write(s)
print("main.c patched")
