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
			input name: "ventSwitch", type: "capability.switchLevel", title: "Vent controller?", multiple: false, required: true
            input name: "pollVent", type: "bool", title: "Poll this vent (for setLevel state)?", defaultValue: false, required: true
            input name: "minVent", type: "decimal", title: "Minimum vent level?", defaultValue: "10", required: true
            
            paragraph ""
            input name: "thermometer", type: "capability.temperatureMeasurement", title: "Room thermometer?", multiple: false, required: true
			
            paragraph ""
            input name: "thermostatOne", type: "capability.thermostat", title: "1st Thermostat", multiple: false, required: true
			input name: "thermostatTwo", type: "capability.thermostat", title: "2nd Thermostat", multiple: false, required: false  // allow for tracking a single zone only
            input name: "pollTstats", type: "bool", title: "Poll these thermostats? (for state changes)?", defaultValue: true, required: true
            
            input name: "trackTempChanges", type: "capability.temperatureMeasurement", title: "Track temp change events elsewhere?", multiple:true, required: false
		}

		section("Vent control parameters:") {
        
        	input name: "tempControl", type: "bool", title: "Actively manage room/zone temps?", defaultValue: false, required: true, refreshAfterSelection: true
			input name: "followMe", type: "capability.thermostat", title: "Follow temps on this thermostat", multiple: false, required: false, defaultValue: "${thermostatOne}"

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
    
    atomicState.lastStatus = ""
    atomicState.checking = false
    atomicState.ventChanged = true
    atomicState.timeHandlerLast = false
    
// Get the latest values from all the devices we care about BEFORE we subscribe to events...this avoids a race condition at installation 
// & reconfigure time

	ventSwitch.setLevel(minVent as Integer)
    if (pollVent) { ventSwitch.refresh() }	// get the current / latest status of everything
    atomicState.ventChanged = false		// just polled for the latest, don't need to poll again until we change the setting (battery saving)

	thermometer.refresh()				// get the latest temperature from the room
    if (pollTstats) { 
    	pollThermostats()
    	runIn( 600, timeHandler, [overwrite: true] )  // schedule another poll in 10 minutes (assume things are quiet)
    }

// Since we have to poll the thermostats to get them to tell us what they are doing, we need to track events that might indicate
// one of the zones has changed from "idle", so we subscribe to events that could indicate this change. Ideally, we don't have to also
// use scheduled polling - temp/humidity changes around the house should get us checking frequently enough - and at more usefule times
	
	subscribe(thermostatOne, "thermostatOperatingState", tHandler)
	if (thermostatTwo) { subscribe(thermostatTwo, "thermostatOperatingState", tHandler) }
    
    subscribe(thermometer, "temperature", tempHandler)
    subscribe(thermometer, "humidity", tempHandler)					// humidty may change before temp (assume a temp/humidity device)
    
    if (tempControl) {
    	subscribe(followMe, "heatingSetpoint", tHandler)			// Need to know when these change (night, home, away, etc.)
        subscribe(followMe, "coolingSetpoint", tHandler)
    }
    if (trackTempChanges) {
    	subscribe(trackTempChanges, "temperature", tempHandler)		// other thermometers may send temp change events sooner than the room
    }

// Let the event handlers do the first check
	checkOperatingStates()				// and finally, setup the vent for our current state
}

def tHandler( evt ) {
	log.trace "tHandler $evt.device.label $evt.name: $evt.value"
	
//    atomicState.lastPoll = new Date().time // if we hear from the thermostat(s), we don't need to poll again for a while
    
	checkOperatingStates()
}
	

def checkOperatingStates() {

	if (atomicState.checking) { 
    	log.info "Already checking"
        return
    }
    atomicState.checking = true
    log.info "Checking"
    
	def activeNow = 0
    def stateNow = 'idle'
    
    def opStateOne = thermostatOne.currentValue('thermostatOperatingState')
	if (opStateOne != 'idle') {
    	activeNow = activeNow + 1
        stateNow = opStateOne
    }
    
    def opStateTwo = 'idle'
    
    if (thermostatTwo) { opStateTwo = thermostatTwo.currentValue('thermostatOperatingState') }
	if (opStateTwo != 'idle') {
    	activeNow = activeNow + 1
       	stateNow = opStateTwo
    }

	log.trace "stateNow: $opStateOne $opStateTwo $stateNow, activeNow: $activeNow"
    def currentStatus = "$stateNow $activeNow"
    
	if (currentStatus != atomicState.lastStatus) {
    	atomicState.lastStatus = currentStatus
    	if (atomicState.ventChanged) {					// if we changed the vent last time, poll to make sure it's still set
        	if (pollVent) { ventSwitch.poll() }			// shouldn't need to poll if the vent's device driver reports setLevel updates correctly
            atomicState.ventChanged = false
        }
        if (tempControl) {
        	thermometer.refresh()						// be sure we are working with the current temperature
        }
         
    	log.info "${ventSwitch.device.label} is ${ventSwitch.currentLevel}%"
        
        if ((activeNow == 0) && pollTstats) {				// (re)schedule the next timed poll for 5 minutes if we just switched to both being idle
        	runIn( 300, timeHandler, [overwrite: true] )  	// it is very unlikely that heat/cool will come on in next 5 minutes
        }
    	else if (activeNow == 1) {
            if (stateNow == "cooling") {
            	def coolLevel = minVent
            	if (thermostatTwo) {						// if we're monitoring two zones (on the same system)
                	coolLevel = 99							// we always have to dump extra cold air when only 1 zone open
                }
                else {
                    if (tempControl) {						// if only 1 Tstat, we manage to the target temperature
                    	if ( thermometer.currentTemperature > followMe.currentValue('coolingSetpoint') ) { coolLevel = 99 }
                    }
                }
    			if ( ventSwitch.currentLevel != coolLevel ) {
        			log.trace "Cooling, ${coolLevel}% vent"
        			ventSwitch.setLevel(coolLevel as Integer)
                    atomicState.ventChanged = true
                }
        	}
            else if (stateNow == "heating") {
            	def heatLevel = minVent
                if (tempControl) {
                	log.debug "temp: $thermometer.currentTemperature target: ${followMe.currentValue('heatingSetpoint')}"
                	if ( thermometer.currentTemperature < followMe.currentValue("heatingSetpoint") ) { heatLevel = 99 }
                }
    			if ( ventSwitch.currentLevel != heatLevel ) {
        			log.trace "Heating, ${heatLevel}% vent"
        			ventSwitch.setLevel(heatLevel as Integer)
                    atomicState.ventChanged = true
        		}  		
            }
            else if (stateNow == "fan only") {
            	def fanLevel = minVent
               	if (tempControl) { fanLevel = 99 } 		// refresh the air if only managing 1 zone/room
     			if ( ventSwitch.currentLevel != fanLevel ) {
        			log.trace "Fan Only, ${fanLevel}% vent"
        			ventSwitch.setLevel(fanLevel as Integer)
                    atomicState.ventChanged = true
                }
            }
        }
    	else if (activeNow == 2) {
			if (stateNow == "cooling") {
            	def coolLevel = 0				// no cooling unless we're managing the temperature
                if (tempControl) {
                	if (thermometer.currectTemperature > followMe.currentValue("coolingSetpoint") ) { coolLevel = 99 }
                }
    			if ( ventSwitch.currentLevel != coolLevel ) {
        			log.trace "Dual cooling, ${coolLevel}% vent"
        			ventSwitch.setLevel(coolLevel as Integer)
                    atomicState.ventChanged = true
        		}
            }
            else if (stateNow == "heating") {
            	def heatLevel = 0				// no heating unless we're managing the temperature
                if (tempControl) {
                	if ( thermometer.currentTemperature < followMe.currentValue("heatingSetpoint") ) { heatLevel = 99 }
                }
    			if ( ventSwitch.currentLevel != heatLevel ) {
        			log.trace "Dual heating, ${heatLevel}% vent"
        			ventSwitch.setLevel(heatLevel as Integer)
                    atomicState.ventChanged = true
        		}  		
            }
            else if (stateNow == "fan only") {
            	def fanLevel = minVent				// no fan unless we're managing the temperature (then only a little)
                if (tempControl) { fanLevel = 30 }
     			if ( ventSwitch.currentLevel != fanLevel ) {
        			log.trace "Dual fan only, ${fanLevel}% vent"
        			ventSwitch.setLevel(fanLevel as Integer)
                    atomicState.ventChanged = true
                }
            }
    	}
    }
    atomicState.checking = false
    log.info "Done!"
}


def tempHandler(evt) {
	log.trace "tempHandler $evt.device.label $evt.name: $evt.value"
    
    atomicState.timeHandlerLast = false

// Limit polls to no more than 1 per 5 minutes (if required: Nest & Ecobee require, native Zwave typically don't)
	if (pollTstats && secondsPast( state.lastPoll, 300)) {
    	log.trace "tempHandler polling"
        pollThermostats() 
        runIn( 300, timeHandler, [overwrite: true] )	// schedule a timed poll in no less than 5 minutes after this one
    }

// if we are managing the temperature, check if we've reached the target when the temp changes in the room
    if (tempControl) { 									
    	if ((evt.device.label == thermometer.label) && (evt.name == "temperature")) {
        	checkOperatingStates()
        }
    }
}

def timeHandler() {
    	log.trace "timeHandler polling"
        
        if (state.timeHandlerLast) {
        	if (state.checking) {
        		atomicState.checking = false				// hack to ensure we do get locked out by a missed state change (happens)
            }
        	atomicState.timeHandlerLast = false			// essentially, if timehandler initiates the poll 2 times in a row while checking=true, reset chacking
        }
        else {
        	if (state.checking) {
            	atomicState.timeHandlerLast = true
            }
        }
        
    	if (pollTstats) { 
        	pollThermostats()
        	runIn( 600, timeHandler, [overwrite: true] )  // schedule a poll in 10 minutes if things are quiet
        }
}

def pollThermostats() {
	if (pollTstats) {
    	thermostatOne.poll()
    	if (thermostatTwo) { thermostatTwo.poll() }	// Can be used for single-zone vent control also
    	atomicState.lastPoll = new Date().time
    }
    else {
    	atomicState.lastPoll = 0
    }
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
