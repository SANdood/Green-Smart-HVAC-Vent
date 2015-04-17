/**
 *  Green Smart HVAC  Vent
 *
 *  Copyright 2014 Barry A. Burke
 *
 *
 * For usage information & change log: https://github.com/SANdood/Green-Smart-HVAC-Vent
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
	name:		"Green Smart HVAC Vent",
	namespace: 	"Green Living",
	author: 	"Barry A. Burke",
	description: "Intelligent vent controller that manages both pressure purge and room temperature.",
	category: 	"Green Living",
	iconUrl: 	"https://s3.amazonaws.com/smartapp-icons/Solution/comfort.png",
	iconX2Url:	"https://s3.amazonaws.com/smartapp-icons/Solution/comfort@2x.png"
)

preferences {
	page( name: "setupApp" )
}

def setupApp() {
	dynamicPage(name: "setupApp", title: "Smart HVAC Vent Setup", install: true, uninstall: true) {

		section("HVAC Vent Controls") {
			input name: "ventSwitch", type: "capability.switchLevel", title: "Vent contrtoller?", multiple: false, required: true
			input name: "thermometer", type: "capability.temperatureMeasurement", title: "Room thermometer?", multiple: false, required: true
			input name: "thermostatOne", type: "capability.thermostat", title: "1st Thermostat", multiple: false, required: true
			input name: "thermostatTwo", type: "capability.thermostat", title: "2nd Thermostat", multiple: false, required: true
            input name: "trackTempChanges", type: "capability.temperatureMeasurement", title: "Track temp change events?", multiple:true, required: false
		}

		section("Vent control parameters:") {
        
        	input name: "tempControl", type: "bool", title: "Actively manage room/zone temps?", defaultValue: true, required: true, refreshAfterSelection: true
			input name: "followMe", type: "capability.thermostat", title: "Follow temps on this thermostat", multiple: false, required: false

			paragraph ""
			input name: "modeOn",  type: "mode", title: "Enable only in specific mode(s)?", multiple: true, required: false
		}
		
		section([mobileOnly:true]) {
			label title: "Assign a name for this SmartApp", required: false
//			mode title: "Set for specific mode(s)", required: false
		}
	}
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
//	unschedule()
	initialize()
}

def initialize() {
	log.debug "Initializing"
    
    atomicState.lastTime = ""
    atomicState.checking = false
    
// Since we have to poll the thermostats to get them to tell us what they are doing, we need to track events that might indicate
// one of the zones has changed from "idle", so we subscribe to events that could indicate this change. Ideally, we don't have to also
// use scheduled polling - temp/humidity changes around the house should get us checking frequently enough - and at more usefule times
	
	subscribe(thermostatOne, "thermostatOperatingState", tHandler)
	subscribe(thermostatTwo, "thermostatOperatingState", tHandler)
    
    subscribe(thermometer, "temperature", tempHandler)
    subscribe(thermometer, "humidity", tempHandler)					// humidty may change before temp
    
    if (followMe) {
    	subscribe(followMe, "heatingSetpoint", tHandler)
        subscribe(followMe, "coolingSetpoint", tHandler)
    }
    if (trackTempChanges) {
    	subscribe(trackTempChanges, "temperature", tempHandler)		// other thermometers may send temp change events sooner than the room
    }

    ventSwitch.poll()					// get the current / latest status of everything
    thermometer.refresh()
    pollThermostats()
    runIn( 600, timeHandler, [overwrite: true] )  // schedule a poll in 10 minutes if things are quiet
    
    checkOperatingStates()				// and setup for where we are now
}

def tHandler( evt ) {
	log.trace "tHandler $evt.device.label $evt.name: $evt.value"
	
//    atomicState.lastPoll = new Date().time // if we hear from the thermostat(s), we don't need to poll again for a while
    
	checkOperatingStates()
}
	

def checkOperatingStates() {

	if (state.checking) { 
    	log.info "Already checking"
        return
    }
    atomicState.checking = true
    log.info "Checking..."
    
	def activeNow = 0
    def stateNow = 'idle'
    
    def opStateOne = thermostatOne.currentValue('thermostatOperatingState')
//    log.debug "T1 $opStateOne"
	if (opStateOne != 'idle') {
    	activeNow = activeNow + 1
        stateNow = opStateOne
    }
    
    def opStateTwo = thermostatTwo.currentValue('thermostatOperatingState')
//    log.debug "T2 $opStateTwo"
	if ( opStateTwo != 'idle') {
    	activeNow = activeNow + 1
       	stateNow = opStateTwo
    }

	log.trace "stateNow: $stateNow, activeNow: $activeNow"
    def fooState = "$stateNow $activeNow"
    
	if (fooState != state.lastTime) {
    	atomicState.lastTime = fooState
    	ventSwitch.poll()
        if (tempControl) {
        	thermometer.refresh()					// be sure we are working with the current temperature
        }
         
    	log.info "${ventSwitch.device.label} is ${ventSwitch.currentLevel}%"
    	if (activeNow == 1) {
            if (stateNow == "cooling") {				// we always have to dump extra cold air when only 1 zone open
    			if ( ventSwitch.currentLevel < 99 ) {
        			log.trace "Cooling, 99% vent"
        			ventSwitch.setLevel(99 as Integer)
                }
        	}
            else if (stateNow == "heating") {
            	def heatLevel = 10
                if (tempControl) {
                	if ( thermometer.currentTemperature < followMe.currentValue('heatingSetPoint') ) { heatLevel = 99 }
                }
    			if ( ventSwitch.currentLevel != heatLevel ) {
        			log.trace "Heating, ${heatLevel}% vent"
        			ventSwitch.setLevel(heatLevel as Integer)
        		}  		
            }
            else if (stateNow == "fan only") {
            	def fanLevel = 50
     			if ( ventSwitch.currentLevel != fanLevel ) {
        			log.trace "Fan Only, ${fanLevel}% vent"
        			ventSwitch.setLevel(fanLevel as Integer)
                }
            }
        }
    	else if (activeNow == 2) {
			if (stateNow == "cooling") {
            	def coolLevel = 0
                if (tempControl) {
                	if (thermometer.currectTemperature > followMe.currentValue("coolingSetPoint") ) { coolLevel = 30 }
                }
    			if ( ventSwitch.currentLevel != coolLevel ) {
        			log.trace "Dual cooling, ${coolLevel}% vent"
        			ventSwitch.setLevel(coolLevel as Integer)
        		}
            }
            else if (stateNow == "heating") {
            	def heatLevel = 0
                if (tempControl) {
                	if ( thermometer.currentTemperature < followMe.currentValue("heatingSetPoint") ) { heatLevel = 30 }
                }
    			if ( ventSwitch.currentLevel != heatLevel ) {
        			log.trace "Heating, ${heatLevel}% vent"
        			ventSwitch.setLevel(heatLevel as Integer)
        		}  		
            }
            else if (stateNow == "fan only") {
            	def fanLevel = 0
                if (tempControl) { fanLevel = 30 }
     			if ( ventSwitch.currentLevel != fanLevel ) {
        			log.trace "Fan Only, ${fanLevel}% vent"
        			ventSwitch.setLevel(fanLevel as Integer)
                }
            }
    	}
    }
    atomicState.checking = false
    log.info "Done!"
}


def tempHandler(evt) {
	log.trace "tempHandler $evt.device.label $evt.name: $evt.value"

// Limit polls to no more than 1 per 5 minutes
	if (secondsPast( state.lastPoll, 300)) {
    	log.trace "tempHandler polling"
        pollThermostats()
        runIn( 300, timeHandler, [overwrite: true] )  // schedule a poll in no less than 5 minutes after this one
    }
}
def timeHandler() {
    	log.trace "timeHandler polling"
    	pollThermostats()
        runIn( 600, timeHandler, [overwrite: true] )  // schedule a poll in 10 minutes if things are quiet
}

def pollThermostats() {
	atomicState.lastPoll = new Date().time
    thermostatOne.poll()
    thermostatTwo.poll()
}

//check last message so thermostat poll doesn't happen all the time
private Boolean secondsPast(timestamp, seconds) {
	if (!(timestamp instanceof Number)) {
		if (timestamp instanceof Date) {
			timestamp = timestamp.time
		} else if ((timestamp instanceof String) && timestamp.isNumber()) {
			timestamp = timestamp.toLong()
		} else {
			return true
		}
	}
	return (new Date().time - timestamp) > (seconds * 1000)
}
