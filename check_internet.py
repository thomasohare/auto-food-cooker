# Script to list what WiFi networks are available
import network
sta_if = network.WLAN(network.STA_IF)
if sta_if.active():
    print("SSIDs found:")
    for wifi in sta_if.scan():
        print(' * ', wifi[0].decode())
