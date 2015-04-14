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
	iconUrl: 	"https://s3.amazonaws.com/smartapp-icons/GreenLiving/Cat-GreenLiving.png",
	iconX2Url:	"https://s3.amazonaws.com/smartapp-icons/GreenLiving/Cat-GreenLiving@2x.png"
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
		}

		section("Vent control parameters:") {


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
	unschedule()
	initialize()
}

def initialize() {
	log.debug "Initializing"
    
    atomicState.activeThermostats = 0
    atomicState.activeState = "idle"
	
	subscribe(thermostatOne, "thermostat", tHandler )
	subscribe(thermostatTwo, "thermostat", tHandler)
    subscribe(thermometer, "temperature", tempHandler)
    checkOperatingStates()
}

def tHandler( evt ) {
	log.trace "tHandler $evt.name: $evt.value"
	
	checkOperatingStates()
}
	

def checkOperatingStates() {
//	atomicState.activeThermostats = 0	
//	thermostatOne.poll()
//	thermostatTwo.poll()

	def activeNow = 0
    def opStateOne = thermostatOne.currentValue("thermostatOperatingState")
    
	if (opStateOne != 'idle') {
    	activeNow = activeNow + 1
        atomicState.activeState = opStateOne
    }
    
    def opStateTwo = thermostatTwo.currentValue("thermostatOperatingState")
	if ( opStateTwo != 'idle') {
    	activeNow = activeNow + 1
        atomicState.activeState = opStateTwo
    }

	if (state.activeThermostats != activeNow) {
		atomicState.activeThermostats = activeNow
        log.trace "activeThermostats = $activeNow, opState = $state.activeState"
    	log.debug "T1 $opStateOne"
    	log.debug "T2 $opStateTwo"
         
    	log.debug "vent is ${ventSwitch.currentLevel}"
    	if (state.activeThermostats == 1) {
    		ventSwitch.poll()
            if (state.activeState == "cooling") {
    			if ( ventSwitch.currentLevel < 99 ) {
        			log.trace "Cooling, open vent"
        			ventSwitch.setLevel(99 as Integer)
                }
        	}
            else if (state.activeState == "heating") {
    			if ( ventSwitch.currentLevel > 10 ) {
        			log.trace "Heating, partial vent"
        			ventSwitch.setLevel(10 as Integer)
        		}  		
            }
            else if (state.activeState == "fan only") {
     			if ( ventSwitch.currentLevel != 30 ) {
        			log.trace "Heating, partial vent"
        			ventSwitch.setLevel(30 as Integer)
                }
            }
        }
    	else if (state.activeThermostats == 2) {
    		ventSwitch.poll()
    		if ( ventSwitch.currentLevel > 0 ) {
        		log.trace "Dual, close vent"
        		ventSwitch.setLevel(0 as Integer)
        	}
    	}
        // If all are idle, leave vent as-is
        
    	ventSwitch.poll()
    	log.debug "vent is now ${ventSwitch.currentLevel}"
    }
}

def tempHandler(evt) {
	log.debug "tempHandler $evt.name: $evt.value"
    
    thermostatOne.poll()
    thermostatTwo.poll()
    checkOperatingStates()
}

/*
def onHandler(evt) {
	log.debug "onHandler $evt.name: $evt.value"

	turnItOn()
}
         
def turnItOn() { 
    if (state.keepOffNow) { return }				// we're not supposed to turn it on right now

	def turnOn = true
    if (useTargetTemp) {							// only turn it on if not hot enough yet
    	if (targetThermometer.currentTemperature >= targetTemperature) { turnOn = false }
    }
    
    if (turnOn) {
		if (!recircMomentary) {
			if (recircSwitch.currentSwitch != "on") { recircSwitch.on() }
		}
    	else { recircSwitch.on() }
    
    	if (timedOff) {
        	unschedule( "turnItOff" )
    		runIn(offAfterMinutes * 60, "turnItOff", [overwrite: false])
        }
    }
}

def offHandler(evt) {
	log.debug "offHandler $evt.name: $evt.value"

    turnItOff()
}

def turnItOff() {

	def turnOff = true
    if (useTargetTemp) {						// only turn it off if it's hot enough
    	if (targetThermometer.currentTemperature < targetTemperature) { turnOff = false }
    }

	if (turnOff) {
        if (timedOff) { unschedule( "turnItOff" ) }						// delete any other pending off schedules
		if (recircSwitch.currentSwitch != "off" ) { recircSwitch.off() }// avoid superfluous off()s
    }
}
*/ 
