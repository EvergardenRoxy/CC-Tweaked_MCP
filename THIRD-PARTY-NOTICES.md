# Third-party notices

cct-run's own code is licensed under the Mozilla Public License 2.0 (`LICENSE`), except
`engine/src/main/resources/cctrun/harness.lua`, which is derived from CC: Tweaked's
`rom/programs/shell.lua` and is therefore licensed under the ComputerCraft Public License 1.0
(`LICENSES/LicenseRef-CCPL.txt`). Inside a release, harness.lua is packed in `lib/engine-*.jar`.

A release archive (`cct-run-*.zip`) also contains the following unmodified third-party libraries in `lib/`.

## CC: Tweaked (ComputerCraft)

| artifact | version | license |
|---|---|---|
| cc.tweaked:cc-tweaked-1.20.1-core | 1.120.2 | ComputerCraft Public License 1.0 (as declared in the published POM); upstream source files are individually under MPL-2.0 or CCPL |
| cc.tweaked:cc-tweaked-1.20.1-core-api | 1.120.2 | ComputerCraft Public License 1.0 (as declared in the published POM); upstream source files are individually under MPL-2.0 or CCPL |

- Copyright: Daniel Ratcliffe (ComputerCraft) and The CC: Tweaked Developers.
- Licenses: `LICENSES/LicenseRef-CCPL.txt`, `LICENSES/MPL-2.0.txt`.
- Source (at no cost):
  - https://github.com/cc-tweaked/CC-Tweaked/tree/v1.20.1-1.120.2
  - https://maven.squiddev.cc/cc/tweaked/cc-tweaked-1.20.1-core/1.120.2/cc-tweaked-1.20.1-core-1.120.2-sources.jar
  - https://maven.squiddev.cc/cc/tweaked/cc-tweaked-1.20.1-core-api/1.120.2/cc-tweaked-1.20.1-core-api-1.120.2-sources.jar
- CCPL conditions that apply to anyone receiving this archive:
  - a user must own a legally acquired copy of Minecraft;
  - these files remain licensed under the CCPL;
  - their source must stay available at no cost (links above).

## Other libraries

| artifact | version | license | license text | source |
|---|---|---|---|---|
| cc.tweaked:cobalt | 0.9.9 | MIT | `LICENSES/third-party/cobalt-MIT.txt` | https://github.com/cc-tweaked/Cobalt |
| com.google.code.gson:gson | 2.11.0 | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/google/gson |
| com.google.guava:guava | 31.1-jre | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/google/guava |
| com.google.guava:failureaccess | 1.0.1 | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/google/guava |
| com.google.guava:listenablefuture | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/google/guava |
| com.google.errorprone:error_prone_annotations | 2.27.0 | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/google/error-prone |
| com.google.j2objc:j2objc-annotations | 1.3 | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/google/j2objc |
| com.google.code.findbugs:jsr305 | 3.0.2 | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://code.google.com/p/jsr-305/ |
| it.unimi.dsi:fastutil | 8.5.9 | Apache-2.0 | `LICENSES/Apache-2.0.txt` | https://github.com/vigna/fastutil |
| io.netty:netty-buffer, netty-codec, netty-codec-http, netty-codec-socks, netty-common, netty-handler, netty-handler-proxy, netty-resolver, netty-transport, netty-transport-native-unix-common | 4.1.82.Final | Apache-2.0 | `LICENSES/Apache-2.0.txt`, NOTICE: `LICENSES/third-party/netty-NOTICE.txt` | https://github.com/netty/netty/tree/netty-4.1.82.Final |
| com.jcraft:jzlib | 1.1.3 | BSD-3-Clause | `LICENSES/third-party/jzlib-BSD-3-Clause.txt` | https://github.com/ymnk/jzlib |
| org.checkerframework:checker-qual | 3.12.0 | MIT | `LICENSES/third-party/checker-qual-MIT.txt` | https://github.com/typetools/checker-framework |
| org.slf4j:slf4j-api, slf4j-nop | 2.0.16 | MIT | `LICENSES/third-party/slf4j-MIT.txt` | https://github.com/qos-ch/slf4j |

Notes:

- Licenses were taken from each artifact's POM (or its parent POM for Guava, Netty and SLF4J).
- License texts come from the projects themselves:
  - checker-qual and SLF4J: `META-INF/LICENSE.txt` in their jars;
  - jzlib: the header of its source files;
  - Netty: `NOTICE.txt` at tag netty-4.1.82.Final;
  - Cobalt: `LICENSE` in its repository's default branch, since there is no 0.9.9 tag.
