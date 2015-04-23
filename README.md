# Green-Smart-HVAC-Vent
SmartApp to control an HVAC vent such as the EcoNet EV100/EV200 for two different use cases

Background
----------
When configuring a single air handler/condensor for multiple zones, usually a "purge" zone is created to release the pressure that would be caused when only a single zone is running. This purge zone is usually dumped into a bonus room (or the basement) where temperatures aren't really a concern. FWIW, purge zones are required more for cooling than heating (cold air is harder to move, thus more sensitive to ductwork pressures). A purge zone allows your HVAC system to be more efficient, also.

I wanted to use the EcoNet Vent to manage my purge room, which is a rarely used guest bedroom on the 3rd floor that can get VERY hot in the summer if not conditioned.

I also wanted a room temperature controller that allowed for a minimum vent opening greater than 0% (off entirely). The existing SmartApps don't support this - they use only on/off commands to the EcoNet vent; I wanted to allow some airflow/pressure instead of turning a room 100% off. Note that this approach allows a second type of purge handling - bleeding a little of the air off into unused rooms instead of requiring a dump zone. (My house works best with the combo of these two approaches, it seems).

Hence, this SmartApp

Purge Mode
----------
Monitors the thermostatOperatingState for two thermostats (the zones), and takes actions depending upon whether we are trying to control the temperature in the dump zone or not. For optionally controlling the temperature in the purge zone, select which of the two Thermostats $heatingSetpoint/$coolingSetpoint we should follow
 --> If only 1 zone is running, open the vent to release pressure
    --> Only to $minVent if heating or fan only and we aren't trying to control the temperature
    --> All the way if we are cooling (no matter what)
    --> All the way if we are trying to heat/cool the room (and the temp isn't right yet)
 --> If both are running, close the vent to $minVent to limit airflow
    --> Open all the way if we are trying to heat/cool the room (close down to $minVent when target temp is reached)
    
Single Room Mode
----------------
Monitors only a single Thermostat (should be the zone that the vent is on), optionally controlling the temperature by following the temperature setpoints of the thermostat. Works like Purge Mode, although only makes decisions based on the single zone's operating state.

Inside the App
--------------
The app uses a few tricks to minimize overhead and maximize efficiency, many of which are worth noting:

1. <b>Polling thermostats</b> Many thermostats used with SmartThings will require polling in order to find out what they are doing. While this can be expensive, if we can't see when the system starts heating/cooling, we can't react in a timely manner. Thus, the app will (optionally) poll the thermostats every 10 minutes if nothing is going on temperature-wise in the thermometers being monitored. If one or more zones start moving air, the app will poll more quickly, but no more than once every 5 minutes.
2. <b>Polling Vents</b> I found the hard way that the stock EcoNet Vent SmartDevice driver doesn't report setLevel changes (due to a bug, for which I have submitted a fix). Thus, the only way to know what the vent is actually currently set to is to poll it several seconds AFTER sending a setLevel command. There is an option to turn this on - you will need it until the EV100/EV200 device drive is fixed. This SmartApp will ONLY poll if it needs to change the vent setting and the vent setting was recently changed by this app...this serves to minimize the overhead and drain on the EV100/200 batteries
3. <b>Pre-check setLevels</b> The app checks the current setLevel of the vent, and only sends changes if a different setLevel is required. This becuase otherwise the vent will perform its calibration cycle (open 100%, closed 100%, set-to-desired-level) every time we send a setLevel command to it. Ideally the device wouldn't have to do this...but it does, so I compensated.

