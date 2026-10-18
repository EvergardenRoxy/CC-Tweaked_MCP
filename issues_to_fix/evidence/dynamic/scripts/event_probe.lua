local ev = os.pullEvent("probe")
print("event", tostring(ev), type(select(2, os.pullEventRaw)))
