-- SPDX-License-Identifier: MPL-2.0
-- cct-live (mod): reports printError output (the shell prints program errors with it) to the mod's trace.
-- Takes the __cctlive API out of _G first, so user programs never see it. Does nothing without the mod's API.
local bridge = rawget(_G, "__cctlive")
if type(bridge) ~= "table" then return end
_G.__cctlive = nil

local original = printError
local owner = shell
local pack, tostring, concat, pcall = table.pack, tostring, table.concat, pcall

_G.printError = function(...)
    local args = pack(...)
    local parts = {}
    for i = 1, args.n do parts[i] = tostring(args[i]) end
    local program
    if owner and owner.getRunningProgram then
        local ok, running = pcall(owner.getRunningProgram)
        if ok then program = running end
    end
    pcall(bridge.printedError, concat(parts, "\t"), program)
    return original(...)
end
