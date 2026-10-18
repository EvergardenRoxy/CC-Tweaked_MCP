// SPDX-License-Identifier: MPL-2.0

rootProject.name = "cct-run"

include("engine", "cli")

// Two loader variants of the same mod. Shared sources live in mod/src/common; each variant only adds its loader shell.
include("mod-forge", "mod-neoforge")
project(":mod-forge").projectDir = file("mod/forge")
project(":mod-neoforge").projectDir = file("mod/neoforge")
