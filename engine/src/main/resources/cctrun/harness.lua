-- SPDX-FileCopyrightText: 2017 Daniel Ratcliffe (original: rom/programs/shell.lua of CC: Tweaked 1.120.2)
-- SPDX-License-Identifier: LicenseRef-CCPL
--
-- This file contains code adapted from shell.lua's executeProgram, so it is under the ComputerCraft Public
-- License (LICENSES/LicenseRef-CCPL.txt), unlike the rest of cct-run (MPL-2.0).
--
-- cct-run harness: installed as /startup.lua, runs the program under test and reports the outcome to
-- the Java side through the __cctrun API. It mirrors executeProgram in rom/programs/shell.lua
-- (CC: Tweaked v1.20.1-1.120.2) so the program sees the same environment and prints the same errors
-- as when run from the shell.
local path = __CCTRUN_PATH__
local args = { [0] = path, __CCTRUN_ARGS__ }
local report = __cctrun
_G.__cctrun = nil -- keep the reporting API out of the program's globals
local token = __CCTRUN_TOKEN__
local tostring, printError, traceback = tostring, printError, debug.traceback
local pullEventRaw = os.pullEventRaw
local pcall = pcall

term.clear()
term.setCursorPos(1, 1)

local make_package = dofile("rom/modules/main/cc/require.lua").make
local function createShellEnv(dir)
    local env = { shell = shell, multishell = multishell }
    env.require, env.package = make_package(env, dir)
    return env
end

local require
do
    local env = setmetatable(createShellEnv("/rom/programs"), { __index = _ENV })
    require = env.require
end
local exception = require "cc.internal.exception"

local file = fs.open(path, "r")
if not file then
    report.done(token, false, "setup", "cannot open program file: " .. path, nil)
    while true do pullEventRaw() end
end
local contents = file.readAll() or ""
file.close()

local env = setmetatable(createShellEnv(fs.getDir(path)), { __index = _G })
env.arg = args

local func, err = load(contents, "@/" .. path, nil, env)
if not func then
    if #contents < 1024 * 128 then
        local parser = require "cc.internal.syntax"
        if parser.parse_program(contents) then printError(err) end
    else
        printError(err)
    end
    report.done(token, false, "syntax", tostring(err), nil)
    -- Wait for the runner to stop the computer. Shutting down here would clear the screen and reset
    -- redstone outputs, which a program ending in game does not do.
    while true do pullEventRaw() end
end

if settings.get("bios.strict_globals", false) then
    getmetatable(env).__newindex = function(_, name)
        error("Attempt to create global " .. tostring(name), 2)
    end
end

report.start()
local ok, perr, co = exception.try(func, table.unpack(args, 1, #args))
if ok then
    report.done(token, true, nil, nil, nil)
else
    if perr and perr ~= "" then
        pcall(printError, perr)
        pcall(exception.report, perr, co)
    end
    local traceOk, trace = pcall(traceback, co)
    local messageOk, message = pcall(tostring, perr)
    report.done(token, false, "runtime", messageOk and message or "program raised an unprintable error", traceOk and trace or nil)
end
while true do pullEventRaw() end -- see above
