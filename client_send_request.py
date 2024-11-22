# This script sends a single POST request to the AWS Iot Core HTTPS endpoint 
# which causes the "button pusher" to activate.
import requests, json

# Setting values for the endpoint/ certificate locations
endpoint =  "[ENDPOINT]-ats.iot.eu-west-2.amazonaws.com"
cert =  "certs/certificate.pem.crt"
key = "certs/private.pem.key"
topic = "sdk/test/python"

# Creating a POST body containing the JSON read by the pico
message = {"state":"on"}
publish_msg = json.dumps(message).encode('utf-8')

# create and format values for HTTPS request
publish_url = 'https://' + endpoint + ':8443/topics/' + topic + '?qos=1'

# make request
publish = requests.request('POST',
            publish_url,
            data=publish_msg,
            headers={
                    "Content-Type": "application/json"
            },
            cert=[cert, key])

# print results
print("Response status: ", str(publish.status_code))
if publish.status_code == 200:
        print("Response body:", publish.text)