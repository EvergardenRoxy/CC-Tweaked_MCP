-- cct-run harness: installed as /startup.lua, runs the program under test and reports the outcome to
-- the Java side through the __cctrun API. It mirrors executeProgram in rom/programs/shell.lua
-- (CC: Tweaked v1.20.1-1.120.2) so the program sees the same environment and prints the same errors
-- as when run from the shell.
local path = __CCTRUN_PATH__
local args = { [0] = path, __CCTRUN_ARGS__ }
local report = __cctrun
_G.__cctrun = nil -- keep the reporting API out of the program's globals

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
    report.done(false, "syntax", tostring(err), nil)
    -- Wait for the runner to stop the computer. Shutting down here would clear the screen and reset
    -- redstone outputs, which a program ending in game does not do.
    while true do os.pullEventRaw() end
end

if settings.get("bios.strict_globals", false) then
    getmetatable(env).__newindex = function(_, name)
        error("Attempt to create global " .. tostring(name), 2)
    end
end

local ok, perr, co = exception.try(func, table.unpack(args, 1, #args))
if ok then
    report.done(true, nil, nil, nil)
else
    if perr and perr ~= "" then
        printError(perr)
        exception.report(perr, co)
    end
    local traceback = co and debug.traceback(co) or nil
    report.done(false, "runtime", tostring(perr), traceback)
end
while true do os.pullEventRaw() end -- see above
