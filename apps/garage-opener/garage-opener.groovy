import groovy.json.JsonSlurper
/**
 *  Garage Opener v1.0.0
 *
 *  Copyright 2020 Mikhail Diatchenko (@muxa)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

definition(
    name: "Garage Opener",
    namespace: "muxa",
    author: "Mikhail Diatchenko",
    description: "Control your garage door with a switch and optional contact sensors",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

def garageControl = [
		name:				"garageControl",
		type:				"capability.garageDoorControl",
		title:				"Garage Door Control",
		description:		"Use a Virtual Garage Door Control device",
		multiple:			false,
		required:			true
	]

def garageSwitch = [
		name:				"garageSwitch",
		type:				"capability.switch",
		title:				"Garage Switch",
		description:		"Physical switch that controls your garage door",
		multiple:			false,
		required:			true
	]

def isMomentarySwitch = [
		name:				"isMomentarySwitch",
		type:				"bool",
		title:				"Is Momentary Switch?",
		description:		"Does the switch automatically turn off?",
		defaultValue:		false,
		required:			true
	]

def closedContact = [
		name:				"closedContact",
		type:				"capability.contactSensor",
		title:				"Garage Fully Closed Contact",
		description:		"Contact Sensor that indicates when garage is fully closed",
		multiple:			false,
		required:			false
	]

def openContact = [
		name:				"openContact",
		type:				"capability.contactSensor",
		title:				"Garage Fully Open Contact",
		description:		"Contact Sensor that indicates when garage is fully open",
		multiple:			false,
		required:			false
	]

def garageTime = [
		name:				"garageTime",
		type:				"number",
		title:				"Garage opening time (in seconds)",
		description:		"Time it takes for the garage door to open or close",
		defaultValue:		15,
		required:			true
	]

def switchOffDelay = [
		name:				"switchOffDelay",
		type:				"long",
		title:				"Switch off delay (in milliseconds)",
		description:		"Delay before automatically turning the switch off (to act as a momentary switch)",
		defaultValue:		1000,
		required:			true
	]

def reversalDelay = [
		name:				"reversalDelay",
		type:				"long",
		title:				"Reversal delay (in milliseconds)",
		description:		"Delay before turning the switch on again to reverse door direction",
		defaultValue:		1000,
		required:			true
	]

def enableLogging = [
		name:				"enableLogging",
		type:				"bool",
		title:				"Enable Debug Logging?",
		defaultValue:		false,
		required:			true
	]

preferences {
    //dynamicPage(name: "controls", uninstall: true, install: true) {
    page(name: "mainPage", title: "Garage Opener", install: true, uninstall: true) {
        section("<h2>General</h2>") {
            //To allow changing this app's name. Helpful if you need multiple controllers for multiple garage doors
            input "thisName", "text", title: "Name this garage door", defaultValue: "Garage Door", submitOnChange: true
            if (thisName) app.updateLabel("$thisName Controller")
        }

		section("<h2>Controls</h2>") {
            input garageControl
            input garageSwitch
    	}
        
        section("<h2>Contacts</h2>") {
            input closedContact
            input openContact
    	}
        
        section("<h2>Options</h2>") {
            input garageTime
            input switchOffDelay
            input reversalDelay
            input enableLogging
    	}
    }
}

def installed() {
    initialize()
}

def updated() {
    log "Updating with settings: ${settings}"
    
    unsubscribe()
    initialize()
}

def initialize() {
    setupSubscriptions()
    
    atomicState.openingBySwitch = false
    atomicState.doorMoving = false
        
    log "Initialised"
}

def setupSubscriptions() {
//    subscribe(garageControl, "door", garageControlHandler)

    subscribe(garageControl, "requestedCommand", garageControlHandler)
    subscribe(garageControl, "syncRequested.true", sync)

//    subscribe(garageSwitch, "switch", garageSwitchHandler)

    if (closedContact)
        subscribe(closedContact, "contact", garageClosedContactHandler)    
    if (openContact)
        subscribe(openContact, "contact", garageOpenContactHandler)
}

/**
 * This function handles requested commands from a virtual garage door device. This allows separation of commands from actual state.
 * The virtual door's 'door' state should mirror the contact sensors and not be affected by requested commands.
 *
 * @param evt
 * @return
 */
def garageControlHandler(evt) {    
    logDebug "Garage door command requested: ${evt.value}"

    def requestedCommand = evt.value
    if (requestedCommand == 'open') {
        // Make the garage door open. If the garage door is already open or opening, then do nothing
        if (garageControl.currentValue('door') == 'open' || garageControl.currentValue('door') == 'opening') {
            // Do nothing
        } else if (garageControl.currentValue('closed')) {
            // Flip the switch to open it
            logDebug "Opening $thisName"
            pressGarageSwitch()
        } else if (garageControl.currentValue('closing')) {
            logDebug("Reversing $thisName direction")
            stopAndReverseDoorDirection()
        } else {
            //State is 'unknown' or an invalid state, just flip the switch on and see where it goes
            pressGarageSwitch()
        }
    } else if (requestedCommand == 'close') {
        if (garageControl.currentValue('door') == 'close' || garageControl.currentValue('door') == 'closing') {
            // Do nothing
        } else if (garageControl.currentValue('door') == 'open') {
            logDebug "Closing $thisName"
            pressGarageSwitch()
        } else if (garageControl.currentValue('door') == 'opening') {
            logDebug("Reversing $thisName direction")
            stopAndReverseDoorDirection()
        } else {
            //State is unknown or invalid, just flip it on and see what happens
            pressGarageSwitch()
        }
    }

    //Erase command because it was handled
    if (requestedCommand != 'none') {
        garageControl.sendEvent(name: "requestedCommand", value: "none")
    }

/*
IF (Variable Garage door manual action(false) = true(F) [FALSE]) THEN
	IF (Garage Door open(F)  OR 
	Garage Door closed(T) [TRUE]) THEN
		Set Garage door manual action to false
	END-IF
ELSE
	IF (Garage Door opening(F) [FALSE]) THEN
		On: Garage Door Switch
		Off: Garage Door Switch --> delayed: 0:00:00.5
	ELSE-IF (Garage Door closing(F) [FALSE]) THEN
		On: Garage Door Switch
		Off: Garage Door Switch --> delayed: 0:00:00.5
	END-IF
END-IF */
    
//    if (evt.value == 'opening' || evt.value == 'closing') {
//
//        if (atomicState.lastDoorStatus == 'opening' || atomicState.lastDoorStatus == 'closing') {
//            log "Engage garage switch from controller to stop motion and reverse direction"
//            stopAndReverseDoorDirection()
//        } else {
//            startTimeout()
//
//            if (!atomicState.openingBySwitch) {
//                // switch should be driven by the garage controller
//
//                if (atomicState.lastDoorStatus == 'unknown') {
//                    // door stopped mid way
//                    if (evt.value != atomicState.lastDoorAction) {
//                        // want to go into reverse direction, which is what will happen when engaging the switch
//                        log "Engage garage switch from controller"
//                        garageSwitch.on()
//                    } else {
//                        log "Engage garage switch from controller to reverse direction"
//                        reverseDoorDirection()
//                    }
//                } else {
//                    log "Engage garage switch from controller"
//                    garageSwitch.on()
//                }
//            }
//        }
//
//        atomicState.lastDoorAction = evt.value
//    } else {
//        // opening or closing is done or is interrupted
//        atomicState.doorMoving = false
//        atomicState.openingBySwitch = false
//    }
    atomicState.lastDoorStatus = evt.value
}

private def stopAndReverseDoorDirection() {
    // 1. stop motion
    pressGarageSwitch()
    
    // 2. start motion to reverse direction after a delay
    // delay is twice the switch off time so that there's the same delay after switch turns off before it turns on again 
    runInMillis(switchOffDelay.toLong()+reversalDelay.toLong(), garageOnOppositeDirection)
}

private def reverseDoorDirection() {
    // 1. start motion going in the wrong direction
    pressGarageSwitch()
    
    // 2. stop and reverse direction to go in the desired direction after a delay
    // delay is twice the switch off time so that there's the same delay after switch turns off before it turns on again 
    runInMillis(switchOffDelay.toLong()+reversalDelay.toLong(), stopAndReverseDoorDirection)
}

def garageOnOppositeDirection() {
    // since direction can only be change by controller, we would have recorded the intended action as last action
    // however this is a future action, not the last action
    // so reverse it, and then trigger the switch to then drive the controller into the right direction
    atomicState.lastDoorAction = atomicState.lastDoorAction == 'opening' ? 'closing' : 'opening'
    pressGarageSwitch()

    // Change the virtual device to an updated state
    def newVal = garageControl.currentValue('door') == 'opening' ? 'closing' : 'opening'
    garageControl.sendEvent(name: "door", value: newVal)
    startTimeout()
}

/**
 * Acts as a momentary button press on the garage relay switch that controls the door. Assumes that the switch is set to a momentary switch.
 */
def pressGarageSwitch() {
    garageSwitch.on()
//    runInMillis(switchOffDelay.toLong(), garageSwitchOff)
}

/**
 * Watches the switch that controls the garage door and changes
 */
//def garageSwitchHandler(evt) {
//    // logDebug "Garage switch event: ${evt.value}"
//
//    if (evt.value == 'on') {
///*
//IF (NOT Garage Door opening(F)  AND
//NOT Garage Door closing(F) [TRUE]) THEN
//	Set Garage door manual action to true
//	IF (Garage Door open(F) [FALSE]) THEN
//		Garage close: Garage Door
//	ELSE
//		Garage open: Garage Door
//	END-IF
//END-IF */
//
//        atomicState.doorMoving = !atomicState.doorMoving
//        def doorStatus = garageControl.currentValue('door')
//        if (atomicState.doorMoving) {
//            logDebug "Physical door moving"
//            if (doorStatus != 'opening' && doorStatus != 'closing') {
//                // switch should drive the garage controller
//                log "Engage garage controller from switch"
//                atomicState.openingBySwitch = true
//                if (atomicState.lastDoorAction == 'opening') {
//                    garageControl.close()
//                } else {
//                    garageControl.open()
//                }
//            }
//        } else {
//            logDebug "Physical door stopped"
//            if (doorStatus != 'open' && doorStatus != 'closed') {
//                // door stopped in a mid position
//                log.warn "${garageControl.label} stopped while ${doorStatus}"
//                garageControl.sendEvent(name: "door", value: "unknown", descriptionText: "${garageControl.label} stopped while ${doorStatus}")
//            }
//        }
//
//        runInMillis(switchOffDelay.toLong(), garageSwitchOff)
//    }
//}
//
//private def garageSwitchOff() {
//    garageSwitch.off()
//}

def startTimeout() {
    runIn(garageTime.toLong(), handleTimeout)
}

def handleTimeout() {
    def doorStatus = garageControl.currentValue('door')
    if (doorStatus == 'opening') {
        if (openContact) {
            // we have a contact that detects fully open position.
            // however the contact has not yet closed (otherwise the door status would be `open`)
            // this means that the garage door is stuck while opening
            log.warn "${thisName} might be stuck while opening"
            garageControl.sendEvent(name: "door", value: "unknown", descriptionText: "${thisName} might be stuck while opening")
        } else {
            // no contact that detects fully open position.
            // use timeout to set controller to `open`
            garageControl.sendEvent(name: "door", value: "open", descriptionText: "${thisName} is open after ${garageTime}s")
        }
    }
    else if (doorStatus == 'closing') {
        if (closedContact) {
            // we have a contact that detects fully closed position.
            // however the contact has not yet closed (otherwise the door status would be `closed`)
            // this means that the garage door is stuck while closing
            log.warn "${thisName} might be stuck while closing"
            garageControl.sendEvent(name: "door", value: "unknown", descriptionText: "${thisName} might be stuck while closing")
        } else {
            // no contact that detects fully closed position.
            // use timeout to set controller to `closed`
            garageControl.sendEvent(name: "door", value: "closed", descriptionText: "${thisName} is closed after ${garageTime}s")
        }
    }
}

/**
 * Handles a change in the state of the open contact sensor.
 * If the sensor is closed, then the garage door is fully open, set the vitual the same.
 * If the sensor is open, and it's last value was closed, then the garage door has started closing. Set the virtual accordingly.
 * @param evt
 * @return
 */
def garageOpenContactHandler(evt) {    
    //logDebug "Garage open contact event: ${evt.value}"
    if (evt.value == 'closed') {
        log "${openContact.label} detected that $thisName is fully open"
        garageControl.sendEvent(name: "door", value: "open", descriptionText: "${openContact.label} detected that $thisName is fully open")
    } else if (evt.value == 'open' && evt.isStateChange) {
        garageControl.sendEvent(name: "door", value: "closing")
        startTimeout()
    }
}

/**
 * Handles a change in the state of the closed contact sensor.
 * If the sensor's value is closed, then set the virtual garage door the same.
 * If it is open, and the last value was closed, then you know the garage door is opening. Set the virtual accordingly.
 * @param evt
 * @return
 */
def garageClosedContactHandler(evt) {    
    //logDebug "Garage closed contact event: ${evt.value}"
//    def lastValue = closedContact.events(max: 2).get(1).value
    if (evt.value == 'closed') {
        log "${closedContact.label} detected that $thisName is fully closed"
        garageControl.sendEvent(name: "door", value: "closed", descriptionText: "${closedContact.label} detected that $thisName is fully closed")
    } else if (evt.value == 'open' && evt.isStateChange) {
        // Set the virtual to opening as well
        garageControl.sendEvent(name: "door", value: "opening")
        startTimeout()
    }
}

/**
 * Syncs the physical garage door's status to the virtual one using the two contact sensors.
 */
def sync(evt) {
    logDebug "Syncing ${thisName} state"
    if (openContact && closedContact) {
        // Two contacts present
        def closedValue = closedContact.currentValue("contact")
        def openValue = openContact.currentValue("contact")
        if (closedValue == 'closed' && openValue == 'open') {
            garageControl.sendEvent(name: "door", value: "closed", descriptionText: "${closedContact.label} detected that $thisName is fully closed")
        } else if (closedValue == 'open' && openValue == 'closed') {
            garageControl.sendEvent(name: "door", value: "open", descriptionText: "${openContact.label} detected that $thisName is fully open")
        } else if (closedValue == 'open' && openValue == 'open') {
            garageControl.sendEvent(name: "door", value: "unknown", descriptionText: "${thisName} is in unknown state")
        } else {
            // Both contact sensors are closed, this is an invalid state
            log "Error: $thisName Controller found garage door to be in an invalid state"
        }
    }

    garageControl.sendEvent(name: "syncRequested", value: "false")
}

def log(msg) {
	if (enableLogging) {
		log.info msg
	}
}

def logDebug(msg) {
	if (enableLogging) {
		log.debug msg
	}
}