import sys
import ntptime
import ssl
import time
import machine
import network
import ujson
import array
from umqtt.simple import MQTTClient
import rp2
from rp2 import PIO, StateMachine, asm_pio
import uos

# Function to connect to the internet using a given SSID/ password (plus some retry logic)
def connect_to_internet(ssid, password,retries):    
    wlan.connect(SSID, PASS)
    
    # Wait for connect or fail
    max_wait = 30
    # Checking if the connection has been set up or rejected
    while max_wait > 0:
      if wlan.status() < 0 or wlan.status() >= 3:
        break
      # Decrementing the wait value
      max_wait -= 1
      print_and_log(log_file,'waiting for connection...')
      time.sleep(1)
    
    # Checking connection status (see https://datasheets.raspberrypi.com/picow/connecting-to-the-internet-with-pico-w.pdf)
    if wlan.status() != 3:
        status_log = 'WLAN Status: {}'.format(wlan.status())
        print_and_log(log_file,status_log)
        print_and_log(log_file,'network connection failed')
        
        # Checking if the max retries have been reached
        if retries > 3:
            print_and_log(log_file,"Failed to connect after "+str(retries + 1)+" attempts!")
            sys.exit(1)
        
        # Incrementing the retry count and trying again
        retries = retries + 1
        print_and_log(log_file,"Reconnecting after 10 seconds...")
        time.sleep(10)
        connect_to_internet(ssid, password,retries)
    else:
        # Noting connection success
        print_and_log(log_file,'connected')
        # Switching on status led 0
        switch_light_on_or_off("on")
        status_log = 'WLAN Status: {}'.format(wlan.status())
        print_and_log(log_file,status_log)
        #status = wlan.ifconfig() # Can be used to show IP address
        
        # Setting the time
        ntptime.settime()
        
        # Returning the result of the connection made to AWS IoT
        return connect_to_aws_iot(0)


# Function to connect to the MQTT queue
def connect_to_aws_iot(aws_iot_retries):
    # Waiting before trying again (if called again)
    if aws_iot_retries > 0:
        print_and_log(log_file,"Waiting for 10 seconds before reconnecting to AWS IoT")
        time.sleep(10)
    
    # Stopping if unable to connect
    if aws_iot_retries > 3:
        print_and_log(log_file,"Failed to connect to AWS IoT after " + str(aws_iot_retries + 1) + " attempts!")
        sys.exit(1)
    
    print_and_log(log_file,"Connecting to AWS IoT")
    
    try:
        # Setting up connection details fro connecting to the queue.
        # Uses the SSL context established at the "start"
        mqtt_client = MQTTClient(
            client_id=MQTT_CLIENT_ID,
            server=MQTT_ENDPOINT,
            port=8883,
            keepalive=5000,
            ssl=context,
        )
        
        # Connecting to AWS Iot using the 
        mqtt_client.connect()
        print_and_log(log_file,"Connected to AWS IoT...")

        # Setting up a function to run when messages are seen on the queue
        mqtt_client.set_callback(mqtt_subscribe_callback)
        # Subscribing to a queue to wait on
        mqtt_client.subscribe(SUB_TOPIC)

        # Returning the client to use
        return mqtt_client
    except Exception as e:
        print_and_log(log_file,"Error!")
        print_and_log(log_file,str(e))
        print_and_log(log_file,"Retrying to connect to AWS IoT")
        aws_iot_retries += 1
        # Retrying connection again/ returning another client (may fail if None is returned)
        return connect_to_aws_iot(aws_iot_retries)        


# Callback function for all subscriptions
def mqtt_subscribe_callback(topic, msg):
    # Logging the received message
    print_and_log(log_file,"Received topic: %s message: %s" % (topic, msg))
    # Noting the voltage
    read_voltage()
    # Checking if the topic and message values are correct
    if topic == SUB_TOPIC:
        mesg = ujson.loads(msg)
        if 'state' in mesg.keys():
            if mesg['state'] == 'on':
                # Moving the servo if all is OK
                print_and_log(log_file,"pushing button")                
                push_button(servo)


# Moves te servo, note that the values can be altered to alter the location of the servo.
def push_button(servo):
    read_voltage()
    servo.duty_u16(1802)
    read_voltage()
    time.sleep(2)
    read_voltage()
    servo.duty_u16(7864)
    read_voltage()
    time.sleep(2)
    read_voltage()
    servo.duty_u16(1802)
    read_voltage()
    time.sleep(2)
    read_voltage()


# Function to write to a log + write to stdout. Also truncates log if too big
def print_and_log(log_file,text):
    # Get the file size in bytes
    try:
        file_size = uos.stat(log_file)[6]
    except OSError:
        file_size = 0
    
    # Truncating file if it is over 100Kb
    if file_size > 102400:
        print("Truncating log file")
        with open(log_file, 'wb') as file:
            pass
    
    file = open(log_file,"a")    
    print(text)
    file.write(text)
    file.write("\n")
    file.close()


# Reads voltage, using set constants/ conversions to make it readable (0.027 bad)
def read_voltage():    
    conversion_factor = 3.3 / 65535  # Convert ADC value to voltage
    # Read the raw ADC value
    raw_value = adc_pin.read_u16()
    # Convert the raw value to voltage
    voltage = raw_value * conversion_factor
    print_and_log(log_file,str(voltage))


# Switches the LED on pin 0 on or off based on the "flag" given 
def switch_light_on_or_off(flag):
    if flag == "on":
        machine.Pin(0,machine.Pin.OUT) 
        machine.Pin(0).value(1)
    else:
        machine.Pin(0,machine.Pin.OUT) 
        machine.Pin(0).value(0)

# - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - 
# START
# - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - 

# Input variables for connecting to the internet/ AWS IoT
log_file = "button_pusher.log"
MQTT_ENDPOINT = b"[ENDPOINT_HASH]-ats.iot.eu-west-2.amazonaws.com"
MQTT_CLIENT_ID = b"RaspberryPiPicoW"
SUB_TOPIC = b'sdk/test/python'
# Wifi Name / SSID
SSID = b'WIFI NAME'
# Wifi Password
PASS = b'WIFI PASSWORD'

# Servo control setup - - - - 
adc_pin = machine.ADC(28)  # Default ADC pin on the Raspberry Pi Pico W
# Set up PWM Pin for servo control
servo_pin = machine.Pin(6)
servo = machine.PWM(servo_pin)
servo.freq (50)
# - - - - 

# AWS Iot setup - - - -
with open("/certs/pkey.der", "rb") as f:
    PRIVATE_KEY_DER = f.read()
with open("/certs/cert.der", "rb") as f:
    CERT_DER = f.read()
# .cer file is DER format from https://www.amazontrust.com/repository/
with open("/certs/AmazonRootCA1.der", "rb") as f:
    CA_DER = f.read()

context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
context.verify_mode = ssl.CERT_REQUIRED
context.load_cert_chain(CERT_DER, PRIVATE_KEY_DER)
context.load_verify_locations(cadata=CA_DER)
# - - - -

# Internet connection setup - - - -
print_and_log(log_file,"Connecting to internet")
# Making sure power saving mode is turned off for better connection (3.6.3. Power-saving mode in README link)
wlan = network.WLAN(network.STA_IF)
wlan.active(True)
wlan.config(pm = 0xa11140)
# - - - -


# Connecting to the internet/ AWS IoT
mqtt_client = connect_to_internet(SSID,PASS,0)

# Main loop
while True:
    try:
        # Checking that AWS IoT was connected to
        if mqtt_client:
            # Looking for new messages
            mqtt_client.check_msg()
        else:
            # Reconnect if mqtt_client is None
            print_and_log(log_file,"mqtt_client is None")            
            time.sleep(5)
            mqtt_client = connect_to_aws_iot(0)
        time.sleep(5)
        
        # Noted loss of connection to internet, retting connection/ reconnecting to AWS IoT
        if wlan.status() != 3:
            print_and_log(log_file,'network connection failed')
            mqtt_client = connect_to_internet(SSID, PASS, 0)
    # Error handling
    except KeyboardInterrupt:
        print_and_log(log_file,"Execution interrupted")
        # Switching off status led 0
        switch_light_on_or_off("off")
        sys.exit(0)
    except Exception as e:
        print_and_log(log_file,"GENERAL ERROR")
        print_and_log(log_file,str(e))
        switch_light_on_or_off("off")
        sys.exit(1)
