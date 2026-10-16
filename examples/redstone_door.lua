-- Opens a door (redstone on the left) for 1 second whenever the button on the back is pressed.
print("waiting")
while true do
    os.pullEvent("redstone")
    if redstone.getInput("back") then
        redstone.setOutput("left", true)
        sleep(1)
        redstone.setOutput("left", false)
        print("cycled")
    end
end
