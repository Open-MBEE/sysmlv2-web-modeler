package Model {
    package FlashlightStarterModel {
        // ran with 2025-09 release on 2025-10-20
        /* The following is a simple flashlight model that is intended to introduce modelers to SysML v2.
        * The scope includes some basic language features used to model system requirements, behavior, structure, analysis and verification.
        * This model contains usage elements with limited use of definition elements to simplify the example.
        * It is anticipated that a user will review and modify this model in their modeling environment.
        * As an example, a modification could include updating the flashlight requirments and design to add a flashing state.
        *
        * Refer to the following URL for instructional material on SysML v2 textual and graphical syntax: 
        * https://github.com/Systems-Modeling/SysML-v2-Release/tree/master/doc
        *
        */
        private import ScalarValues::*;
        private import SI::*;
        private import USCustomaryUnits::*;
        private import Definitions::*;
        
        package Definitions{
            item def OnOffCmd;
            attribute def DCPwr;
            item def Light;
        }
        package FlashlightSpecificationAndDesign {
            package Requirements {
                requirement flashlightSpecification {
                    requirement userInterface;
                    requirement illumination {
                        requirement fieldOfView {
                            doc /* The flashlight field of view shall be 0 - 20 degrees.*/
                        }
                        requirement lightPower {
                            doc /* The light power shall be a minumum of TBD lumens.*/
                        }
                    }
                    requirement physical {
                        requirement portability;
                        requirement size {
                            doc /* The flashlight shall be less than 6 inches in length.*/
                        }
                        requirement weight {
                            doc /* The weight shall be less than 0.25 lbs.*/
                            attribute actualWeight :> mass;
                            require constraint {
                                actualWeight <= 0.25 [lb]
                            }
                        }
                    }
                    requirement reliability;
                    requirement cost;
                    requirement newRequirement;
                }
            }
            package Actions {
                action produceDirectedLight {
                    in item onOffCmd;
                    out item lightOut = directLight.lightOut;
                    action provideDCPwr { out attribute dcPwr; }
                    action connectDCPwr {
                        in item onOffCmd;
                        in attribute dcPwrIn;
                        out attribute dcPwrOut;
                    }
                    action generateLight {
                        in attribute dcPwrIn;
                        out item light;
                    }
                    action directLight {
                        in item lightIn;
                        out item lightOut;
                    }
                    fork fork1;
                        then provideDCPwr;
                        then connectDCPwr;
                    first start then fork1;
                    bind onOffCmd = connectDCPwr.onOffCmd;
                    bind lightOut = directLight.lightOut;
                    flow provideDCPwr.dcPwr to connectDCPwr.dcPwrIn;
                    flow connectDCPwr.dcPwrOut to generateLight.dcPwrIn;
                    flow generateLight.light to directLight.lightIn;
                }
            }
            package Parts {
                part flashlight {
                    attribute mass :> ISQBase::mass;
                    attribute fov : Real;
                    attribute illuminationLevel : Real;
                    port cmdPort;
                    port lightOutPort;
                    port handPort;
                    perform Actions::produceDirectedLight;
                    exhibit state flashlightStates {
                        state off;
                        state on {
                            do produceDirectedLight;
                        }
                        transition first start then off;
                        transition off_To_on first off accept OnOffCmd via cmdPort then on;
                        transition on_To_off first on accept OnOffCmd via cmdPort then off;
                    }
                    ref part battery [2] {
                        port dcPwrOutPort {
                            attribute v :> voltage;
                            out attribute i :> electricCurrent;
                            out attribute dcPwr :> ISQMechanics::power = v*i;
                        }
                        perform produceDirectedLight.provideDCPwr;
                    }
                    part switch {
                        port cmdPort;
                        port inPort {
                            in attribute dcPwrIn : DCPwr;
                        }
                        port outPort {
                            out attribute dcPwrOut : DCPwr;
                        }
                        perform produceDirectedLight.connectDCPwr;
                    }
                    part lamp {
                        attribute efficiency : Real;
                        port dcPwrInPort {
                            in attribute dcPwrIn : DCPwr;
                        }
                        port lightOutPort {
                            out item light : Light;
                        }
                        perform produceDirectedLight.generateLight;
                    }
                    part optics {
                        port lightInPort {
                            in item light : Light;
                        }
                        port lightOutPort {
                            out item directedLight : Light;
                        }
                        perform produceDirectedLight.directLight;
                        part reflector {
                            attribute radius :> length;
                        }
                        part lens;
                    }
                    part structure {
                        port handPort;
                        part frontHousing;
                        part middleHousing;
                        part backHousing;
                    }
                    bind cmdPort = switch.cmdPort;
                    bind lightOutPort = optics.lightOutPort;
                    bind handPort = structure.handPort;
                    connect battery.dcPwrOutPort to switch.inPort {
                        flow of DCPwr from source.dcPwr to target.dcPwrIn;
                    }
                    connect switch.outPort to lamp.dcPwrInPort {
                        flow of DCPwr from source.dcPwrOut to target.dcPwrIn;
                    }
                    connect lamp.lightOutPort to optics.lightInPort {
                        flow of Light from source.light to target.light;
                    }
                }
            }
            package RequirementsAllocation {
                private import Requirements::*;
                private import Parts::*;
                allocate flashlightSpecification to flashlight {
                    allocate flashlightSpecification.physical.weight to flashlight.mass;
                    allocate flashlightSpecification.illumination to flashlight.produceDirectedLight;
                    allocate flashlightSpecification.illumination.fieldOfView to flashlight.fov;
                    allocate flashlightSpecification.illumination.lightPower to flashlight.illuminationLevel;
                }
            }
        }
        package Analysis {
            calc def BatteryPower {
                in attribute voltage :> ISQElectromagnetism::voltage;
                in attribute current :> electricCurrent;
                attribute numberBatteriesInSeries : Integer default 2;
                return power :> ISQ::power = numberBatteriesInSeries * voltage * current;
            }
            attribute batteryPower :> ISQ::power = BatteryPower(1.5 [V], 2.1 / 30 [A]);
            /* Note: current = currrent time (2.1 *3600 [A * s])/ battery life (30 *3600 [s])*/
        }
        package Verification {
            private import FlashlightSpecificationAndDesign::Requirements::*;
            private import FlashlightSpecificationAndDesign::Parts::*;
            public import VerificationCases::*;
            verification massTest {
                subject systemUnderTest :> flashlight;
                objective {
                    doc /* Verify system under test satisfies its weight requirement*/
                    verify flashlightSpecification.physical.weight;
                }
                //@VerificationMethod { kind = (kind::test, kind::analyze); }
                action placeSystemOnScale;
                action weighSystem;
                action evaluateResult;
                action removeSystemFromScale;
            }
        }
        package Views1{
            //private import Views::*;
            view flashlightPartsTree{
                expose FlashlightSpecificationAndDesign::Parts::**;
                filter @SysML::PartUsage;
                render Views::asTreeDiagram;
            }
        }
    }

    package FlashlightContextClassExercise{
        private import FlashlightStarterModel::*;
        private import FlashlightStarterModel::Definitions::*;
        package ContextPkg_1{
            package Requirements {
                requirement illuminationRequirement{
                    doc /*Provide a capability for a user to illuminate their surrounding region*/
                }
            }
            package Actions{
            }
            package Parts{
            }  
        }
        package ContextPkg_2{
            package Requirements {
                requirement illuminationRequirement{
                    doc /*Provide a capability for a user to illuminate their surrounding region*/
                }
            }
            package Actions{
            }
            package Parts{
                part context{
                    attribute illuminationArea;
                    part user;
                    part flashlight :> FlashlightSpecificationAndDesign::Parts::flashlight;
                    part environment {
                        part reflectingSource{
                            attribute reflectivity;
                        }
                    }
                }
            }  
        }
        package ContextPkg_3{
            package Requirements {
                requirement illuminationRequirement{
                    doc /*Provide a capability for a user to illuminate their surrounding region*/
                }
            }
            package Actions{
            }
            package Parts{
                part context{
                    attribute illuminationArea;
                    part user{
                        port p1 {
                            out item onOffCmd;
                        }
                        port p2 {
                            in item light;
                        }
                    }
                    part flashlight :> FlashlightSpecificationAndDesign::Parts::flashlight{
                        port cmdPort :>> cmdPort{
                            in item onOffCmd;
                        }
                        port lightOutPort :>> lightOutPort{
                            out item light;
                        }
                    }
                    part environment {
                        part reflectingSource{
                            attribute reflectivity;
                            port p1 {
                                in item incidentLight;
                            }
                            port p2 {
                                out item reflectedLight;
                            }
                        }
                    }
                    connect user.p1 to flashlight.cmdPort {
                        flow of OnOffCmd from source.onOffCmd to target.onOffCmd;
                    }
                    connect flashlight.lightOutPort to environment.reflectingSource.p1 {
                        flow of Light from source.light to target.incidentLight;
                    }
                    connect environment.reflectingSource.p2 to user.p2 {
                        flow of Light from source.reflectedLight to target.light;
                    }
                }
            }  
        }
        package ContextPkg_4{
            package Requirements {
                requirement illuminationRequirement{
                    doc /*Provide a capability for a user to illuminate their surrounding region*/
                }
            }
            package Actions{
                action illuminateRegion{
                    action turnLightOnOff;
                    action produceDirectedLight :> FlashlightSpecificationAndDesign::Actions::produceDirectedLight;
                    action reflectLight;
                    action viewLight;
                }
            }
            package Parts{
                part context{
                    attribute illuminationArea;
                    part user{
                        port p1 {
                            out item onOffCmd;
                        }
                        port p2 {
                            in item light;
                        }
                    }
                    part flashlight :> FlashlightSpecificationAndDesign::Parts::flashlight{
                        port cmdPort :>> cmdPort{
                            in item onOffCmd;
                        }
                        port lightOutPort :>> lightOutPort{
                            out item light;
                        }
                    }
                    part environment {
                        part reflectingSource{
                            attribute reflectivity;
                            port p1 {
                                in item incidentLight;
                            }
                            port p2 {
                                out item reflectedLight;
                            }
                        }
                    }
                    connect user.p1 to flashlight.cmdPort {
                        flow of OnOffCmd from source.onOffCmd to target.onOffCmd;
                    }
                    connect flashlight.lightOutPort to environment.reflectingSource.p1 {
                        flow of Light from source.light to target.incidentLight;
                    }
                    connect environment.reflectingSource.p2 to user.p2 {
                        flow of Light from source.reflectedLight to target.light;
                    }
                }
            }  
        }
        package ContextPkg_5{
            package Requirements {
                requirement illuminationRequirement{
                    doc /*Provide a capability for a user to illuminate their surrounding region*/
                }
            }
            package Actions{
                action illuminateRegion{
                    action turnLightOnOff{
                        out item onOffCmd;
                    }
                    action produceDirectedLight :> FlashlightSpecificationAndDesign::Actions::produceDirectedLight{
                        in item onOffCmd :>> onOffCmd;
                        out item lightOut :>> lightOut;
                    }
                    action reflectLight{
                        in item incidentLight;
                        out item reflectedLight;
                    }
                    action viewLight{
                        in item light;
                    }
                    first start then turnLightOnOff;
                    flow from turnLightOnOff.onOffCmd to produceDirectedLight.onOffCmd;
                    flow from produceDirectedLight.lightOut to reflectLight.incidentLight;
                    flow from reflectLight.reflectedLight to viewLight.light;
                }
            }
            package Parts{
                part context{
                    attribute illuminationArea;
                    part user{
                        port p1 {
                            out item onOffCmd;
                        }
                        port p2 {
                            in item light;
                        }
                    }
                    part flashlight :> FlashlightSpecificationAndDesign::Parts::flashlight{
                        port cmdPort :>> cmdPort{
                            in item onOffCmd;
                        }
                        port lightOutPort :>> lightOutPort{
                            out item light;
                        }
                    }
                    part environment {
                        part reflectingSource{
                            attribute reflectivity;
                            port p1 {
                                in item incidentLight;
                            }
                            port p2 {
                                out item reflectedLight;
                            }
                        }
                    }
                    connect user.p1 to flashlight.cmdPort {
                        flow of OnOffCmd from source.onOffCmd to target.onOffCmd;
                    }
                    connect flashlight.lightOutPort to environment.reflectingSource.p1 {
                        flow of Light from source.light to target.incidentLight;
                    }
                    connect environment.reflectingSource.p2 to user.p2 {
                        flow of Light from source.reflectedLight to target.light;
                    }
                }
            }  
        }
        package ContextPkg_6{
            package Requirements {
                requirement illuminationRequirement{
                    doc /*Provide a capability for a user to illuminate their surrounding region*/
                }
            }
            package Actions{
                action illuminateRegion{
                    action turnLightOnOff{
                        out item onOffCmd;
                    }
                    action produceDirectedLight :> FlashlightSpecificationAndDesign::Actions::produceDirectedLight{
                        in item onOffCmd :>> onOffCmd;
                        out item lightOut :>> lightOut;
                    }
                    action reflectLight{
                        in item incidentLight;
                        out item reflectedLight;
                    }
                    action viewLight{
                        in item light;
                    }
                    first start then turnLightOnOff;
                    flow from turnLightOnOff.onOffCmd to produceDirectedLight.onOffCmd;
                    flow from produceDirectedLight.lightOut to reflectLight.incidentLight;
                    flow from reflectLight.reflectedLight to viewLight.light;
                }
            }
            package Parts{
                part context{
                    attribute illuminationArea;
                    perform Actions::illuminateRegion;
                    part user{
                        port p1 {
                            out item onOffCmd;
                        }
                        port p2 {
                            in item light;
                        }
                        perform illuminateRegion.turnLightOnOff;
                        perform illuminateRegion.viewLight;
                    }
                    part flashlight :> FlashlightSpecificationAndDesign::Parts::flashlight{
                        port cmdPort :>> cmdPort{
                            in item onOffCmd;
                        }
                        port lightOutPort :>> lightOutPort{
                            out item light;
                        }
                        perform illuminateRegion.produceDirectedLight :>> produceDirectedLight;
                    }
                    part environment {
                        perform illuminateRegion.reflectLight;
                        part reflectingSource{
                            attribute reflectivity;
                            port p1 {
                                in item incidentLight;
                            }
                            port p2 {
                                out item reflectedLight;
                            }
                        }
                    }
                    connect user.p1 to flashlight.cmdPort {
                        flow of OnOffCmd from source.onOffCmd to target.onOffCmd;
                    }
                    connect flashlight.lightOutPort to environment.reflectingSource.p1 {
                        flow of Light from source.light to target.incidentLight;
                    }
                    connect environment.reflectingSource.p2 to user.p2 {
                        flow of Light from source.reflectedLight to target.light;
                    }
                }
            }  
        }
    }

    package GeneralConcepts {
        private import ScalarValues::*;
        private import SI::*;
        private import USCustomaryUnits::*;
        package AttributeDeclarationExamples {
            attribute x1 : Integer = 1;
            attribute x2 : Real = 3.3;
            attribute x3 : Boolean = true;
            attribute x4 : String = "This is a String";
            attribute y1 :> length = 3.0 [m];
            attribute y2 :> mass = 100 [kg];
            attribute z1 :> length = 3.0 [ft];
            attribute z2 :> mass = 100 [lb];
        }
        package DefinitionAndUsageExample {
            part def Part0 {
                attribute x;
            }
            part def Part1 :> Part0 { // inherits x
                attribute x :>> x = 1;  // redefines x
                attribute y;           // adds y
            }
            part part1 :Part1; // inherits x,y;
            part part2 :Part1{ // inherits x,y{
                attribute y :>> y = 3; // redefines y
            }
            part part3 :> part2{ // inherits x, y
                attribute z [2];     // adds x [2]
            }
            part part4 :> part3{ // inherits x.y.z
                attribute x1 [1] :> x; // subsets x
                attribute x2 [1] :> x; // subsets x
            }
        }
    }
}