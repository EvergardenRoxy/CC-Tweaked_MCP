-- Never yields: CC: Tweaked aborts it after about 7 seconds ("Too long without yielding").
local n = 0
while true do n = n + 1 end
