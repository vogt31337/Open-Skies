/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Represents a ship.
 */
package celestial.Ship;

import cargo.DockingPort;
import cargo.Equipment;
import cargo.Hardpoint;
import cargo.Item;
import cargo.Nozzle;
import cargo.Weapon;
import celestial.Celestial;
import celestial.Explosion;
import celestial.Planet;
import com.jme3.asset.AssetManager;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.util.CollisionShapeFactory;
import com.jme3.material.Material;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import entity.Entity;
import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import jmeplanet.PlanetAppState;
import lib.Faction;
import lib.astral.Parser.Term;
import universe.SolarSystem;
import universe.Universe;

/**
 *
 * @author nwiehoff
 */
public class Ship extends Celestial {

    /*
     * Behaviors are over-arching goals and motivations such as hunting down
     * hostiles or trading. The behave() method will keep track of any
     * variables it needs and call autopilot functions as needed to realize
     * these goals.
     */
    public enum Behavior {

        NONE,
        TEST,
        PATROL,
        SECTOR_TRADE,
        UNIVERSE_TRADE, //requires a jump drive
        SUPPLY_HOMEBASE, //requires a jump drive
        REPRESENT_HOMEBASE, //requires a jump drive
    }

    /*
     * Autopilot functions are slices of behavior that are useful as part of
     * a big picture.
     */
    public enum Autopilot {

        NONE, //nothing
        WAIT, //waiting
        WAITED, //done waiting
        DOCK_STAGE1, //get permission, go to the alignment vector
        DOCK_STAGE2, //fly into docking port
        UNDOCK, //fly out of docking port
        FLY_TO_CELESTIAL, //fly to a celestial
        ATTACK_TARGET, //attack current target
        ALL_STOP, //slow down until velocity is 0
        FOLLOW, //follow a target at a range
    }
    public static final float STOP_LOW_VEL_BOUND = 1.0f;
    public static final float NAV_ANGLE_TOLERANCE = 0.02f;
    public static final float COM_ANGLE_TOLERANCE = 0.008f;
    public static final float ROLL_LOCK = FastMath.PI / 32;
    public static final float STOP_CAUTION = 1.0f;
    public static final float MAX_JUMP_SHIELD_DAMAGE = 0.45f;
    public static final float JUMP_SAFETY_FUEL = 0.25f;
    public static final float TRADER_JD_SAFETY_FUEL = 0.40f;
    public static final float OOS_VEL_LOWBOUND = 5f;

    public enum EngineMode {

        NORMAL,
        NEWTON
    }
    public static final float NORMAL_DAMP = 0.26f;
    public static final float NEWTON_DAMP = 0;
    private Term type;
    //health
    private float shield;
    private float shieldRecharge;
    private float hull;
    private float maxShield;
    private float maxHull;
    //fuel
    private float fuel;
    private float maxFuel;
    //fuel efficiency
    private float burnMultiplier = 1;
    //navigation
    transient Node core;
    transient Node nav;
    private EngineMode engine = EngineMode.NORMAL;
    private float throttle = 0;
    private float pitch = 0;
    private float yaw = 0;
    private float roll = 0;
    //weapons
    private boolean firing = false;
    //central node
    transient Node center;
    //faction
    protected Faction faction;
    //docking
    protected boolean docked = false;
    private DockingPort port;
    //sensor
    private float sensor;
    private Ship target;
    //behavior and autopilot
    protected Autopilot autopilot = Autopilot.NONE;
    protected Behavior behavior = Behavior.NONE;
    //behavior targets
    protected Celestial flyToTarget;
    protected Station homeBase;
    private float range;
    //physics stats
    private float thrust; //engine force
    private float torque; //turning force
    //cargo
    protected double cargo;
    protected ArrayList<Item> cargoBay = new ArrayList<>();
    protected ArrayList<Hardpoint> hardpoints = new ArrayList<>();
    protected ArrayList<Nozzle> nozzles = new ArrayList<>();
    //money
    protected long cash = 0;

    public Ship(Universe universe, Term type, String faction) {
        super(Float.parseFloat(type.getValue("mass")), universe);
        this.type = type;
        //init stats
        initStats();
        initNav();
        initFaction(faction);
    }

    private void initStats() {
        setThrust(Float.parseFloat(getType().getValue("thrust")));
        torque = Float.parseFloat(getType().getValue("torque"));
        setMaxShield(shield = Float.parseFloat(getType().getValue("shield")));
        shieldRecharge = Float.parseFloat(getType().getValue("shieldRecharge"));
        setMaxHull(hull = Float.parseFloat(getType().getValue("hull")));
        setMaxFuel(fuel = Float.parseFloat(getType().getValue("fuel")));
        setSensor(Float.parseFloat(getType().getValue("sensor")));
        setCargo(Float.parseFloat(getType().getValue("cargo")));
        installHardpoints(getType());
        installNozzles(getType());
    }

    private void initNav() {
        core = new Node();
        nav = new Node();
        nav.move(Vector3f.UNIT_Z);
        core.attachChild(nav);
    }

    public void addInitialEquipment(String equip) {
        /*
         * Equips the ship with equipment from the starting loadout
         */
        //equip player from install keyword
        String[] arr = equip.split("/");
        for (int a = 0; a < arr.length; a++) {
            Item test = new Item(arr[a]);
            /*
             * Cannons and launchers are both in the weapon class
             */
            try {
                if (test.getType().equals("cannon") || test.getType().equals("missile")
                        || test.getType().equals("battery") || test.getType().equals("turret")) {
                    Weapon wep = new Weapon(arr[a]);
                    fit(wep);
                }
            } catch (Exception e) {
                //e.printStackTrace();
            }
        }
    }

    private void initFaction(String name) {
        faction = new Faction(name);
    }

    @Override
    public void construct(AssetManager assets) {
        //Get name
        String name = getType().getValue("type");
        //load spatial
        loadSpatial(assets, name);
        //construct model and physics
        center = new Node();
        constructMaterial(assets, name);
        constructPhysics();
        //construct hardpoints
        constructHardpoints(assets);
        constructNozzles(assets);
    }

    @Override
    public void deconstruct() {
        spatial = null;
        mat = null;
        physics = null;
    }

    protected void loadSpatial(AssetManager assets, String name) {
        //load model
        try {
            spatial = assets.loadModel("Models/" + name + "/Model.blend");
        } catch (Exception e) {
            System.out.println("Error: Model for ship " + name + " not found! Using placeholder.");
            spatial = assets.loadModel("Models/UnknownShip/Model.blend");
        }
    }

    protected void constructPhysics() {
        //setup physics
        CollisionShape hullShape = CollisionShapeFactory.createDynamicMeshShape(spatial);
        physics = new RigidBodyControl(hullShape, getMass());
        center.addControl(physics);
        physics.setSleepingThresholds(0, 0);
        physics.setAngularDamping(0.99f); //I do NOT want to deal with this at 0
        center.setName(this.getClass().getName());
        //store physics name control
        nameControl.setParent(this);
        center.addControl(nameControl);
    }

    protected void constructMaterial(AssetManager assets, String name) {
        //load texture
        mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
        mat.setTexture("DiffuseMap",
                assets.loadTexture("Models/" + name + "/tex.png"));
        //setup texture
        spatial.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        spatial.setMaterial(mat);
        //store
        center.attachChild(spatial);
    }

    protected void constructHardpoints(AssetManager assets) {
        for (int a = 0; a < hardpoints.size(); a++) {
            //initialize node
            hardpoints.get(a).initNode();
            //debug
            //hardpoints.get(a).showDebugHardpoint(assets);
            //construct
            hardpoints.get(a).construct(assets);
            //store node with spatial
            center.attachChild(hardpoints.get(a).getNode());
        }
    }

    protected void constructNozzles(AssetManager assets) {
        for (int a = 0; a < nozzles.size(); a++) {
            //initialize node
            nozzles.get(a).initNode();
            //debug
            //nozzles.get(a).showDebugHardpoint(assets);
            //construct
            nozzles.get(a).construct(assets);
            //store node with spatial
            center.attachChild(nozzles.get(a).getNode());
        }
    }

    public Vector3f getRotationAxis() {
        if (nav == null || core == null) {
            initNav();
        }
        /*
         * Returns a vector that represents a position vector being rotated
         * around the axis of the ship.
         */
        Vector3f eul = null;
        {
            core.setLocalRotation(getPhysicsRotation().clone());
            eul = nav.getWorldTranslation().clone();
        }
        return eul;
    }

    /*
     * Methods for autopilot in-system
     */
    private void autopilot() {
        if (autopilot == Autopilot.NONE) {
        } else if (autopilot == Autopilot.FLY_TO_CELESTIAL) {
            autopilotFlyToCelestial();
        } else if (autopilot == Autopilot.ALL_STOP) {
            autopilotAllStop();
        } else if (autopilot == Autopilot.DOCK_STAGE1) {
            autopilotDockStageOne();
        } else if (autopilot == Autopilot.DOCK_STAGE2) {
            autopilotDockStageTwo();
        } else if (autopilot == Autopilot.UNDOCK) {
            autopilotUndock();
        } else if (autopilot == Autopilot.ATTACK_TARGET) {
            autopilotFightTarget();
        }
    }

    private void autopilotFightTarget() {
        if (target != null) {
            if (target.getState() == State.ALIVE) {
                if (target.getCurrentSystem() == getCurrentSystem()) {
                    if (inSensorRange(target)) {
                        double distance = target.getLocation().distance(getLocation());
                        double minRange = getNearWeaponRange();
                        //rotate to face target
                        Vector3f solution = target.getLocation();
                        Vector3f steering = getSteeringData(solution, Vector3f.UNIT_Y);
                        boolean aligned = finePointNoseAtVector(steering, COM_ANGLE_TOLERANCE);
                        //keep at range
                        if (aligned) {
                            if (distance < (minRange / 3)) {
                                /*
                                 * The enemy is getting too close to the ship, so fire the reverse
                                 * thrusters.
                                 */
                                throttle = -1;
                            } else if (distance > (minRange / 2) && distance < (2 * minRange / 3)) {
                                /*
                                 * The enemy is getting too far away from the ship, fire the forward
                                 * thrusters.
                                 */
                                throttle = 1;
                            } else if (distance > (minRange)) {
                                /*
                                 * The enemy is out of weapons minRange and needs to be approached
                                 */
                                float dP = 0;
                                float d1 = getLocation().subtract(target.getLocation()).length();
                                Vector3f dv1 = getLocation().add(getLinearVelocity().mult((float) tpf));
                                Vector3f dv2 = target.getLocation().add(target.getLinearVelocity().mult((float) tpf));
                                float d2 = dv1.subtract(dv2).length();
                                dP = d2 - d1;
                                if (dP + (getAcceleration()) > 0) {
                                    throttle = 1;
                                } else {
                                    throttle = 0;
                                }
                            } else {
                                throttle = 0;
                            }
                            if (distance < minRange) {
                                fireActiveGuns(target);
                            }
                        }
                        if (distance < minRange) {
                            fireActiveTurrets(target);
                        }
                    } else {
                        cmdAbort();
                    }
                } else {
                    cmdAbort();
                }
            } else {
                cmdAbort();
            }
        } else {
            cmdAbort();
        }
    }

    private void autopilotUndock() {
        //get the docking align
        Vector3f align = port.getAlign().getWorldTranslation();
        //fly towards it
        moveToPositionWithHold(align, Float.POSITIVE_INFINITY);
        //abort when hold is reached
        if (physics.getLinearVelocity().length() >= DockingPort.DOCK_SPEED_LIMIT) {
            //all done
            cmdAbortDock();
        }
    }

    private void autopilotDockStageOne() {
        //make sure we have a flyToTarget
        if (flyToTarget != null) {
            //make sure it is a station
            if (flyToTarget instanceof Station && flyToTarget.getState() == State.ALIVE) {
                //make sure we can actually dock there
                Station tmp = (Station) flyToTarget;
                if (tmp.getCurrentSystem() == currentSystem) {
                    if (port == null) {
                        //get the docking port to use
                        port = tmp.requestDockingPort(this);
                    } else {
                        //get the docking align
                        Vector3f align = port.getAlign().getWorldTranslation();
                        //fly to it
                        float distance = align.distance(physics.getPhysicsLocation());
                        float velocity = physics.getLinearVelocity().length();
                        if (distance < port.getSize() / 2) {
                            autopilotAllStop();
                            if (velocity == 0 || autopilot == Autopilot.NONE) {
                                //next stage
                                setAutopilot(Autopilot.DOCK_STAGE2);
                            }
                        } else {
                            //determine correct hold to use
                            float hold = 0;
                            if (distance <= getFlightHold()) {
                                hold = distance;
                            } else {
                                hold = getFlightHold();
                            }
                            //move to position
                            moveToPositionWithHold(align, hold);
                            //detect if autopilot kicked off
                            if (autopilot == Autopilot.NONE) {
                                /*
                                 * moveToPosition() detects when the ship has stopped
                                 * moving and corrects itself by turning off the autopilot.
                                 */
                                setAutopilot(Autopilot.DOCK_STAGE1);
                            } else {
                                //do nothing, we are still on autopilot
                            }
                        }
                    }
                } else {
                    cmdAbortDock();
                }
            } else {
                cmdAbortDock();
            }
        } else {
            cmdAbortDock();
        }
    }

    private void autopilotDockStageTwo() {
        //make sure we have a flyToTarget
        if (flyToTarget != null) {
            //make sure it is a station
            if (flyToTarget instanceof Station && flyToTarget.getState() == State.ALIVE) {
                //make sure we can actually dock there
                Station tmp = (Station) flyToTarget;
                if (tmp.getCurrentSystem() == currentSystem) {
                    if (port == null) {
                        //abort because this is stage 2
                        cmdAbortDock();
                    } else {
                        //get the docking port
                        Vector3f dock = port.getNode().getWorldTranslation();
                        //get the hold
                        float hold = DockingPort.DOCK_SPEED_LIMIT / 2;
                        //fly to it
                        moveToPositionWithHold(dock, hold);
                        //detect if autopilot kicked off
                        if (autopilot == Autopilot.NONE) {
                            setAutopilot(Autopilot.DOCK_STAGE2);
                        } else {
                            if (physics.getLinearVelocity().length() > 0) {
                                //stop rotation
                                pitch = 0;
                                yaw = 0;
                                roll = 0;
                            }
                        }
                    }
                } else {
                    cmdAbortDock();
                }
            } else {
                cmdAbortDock();
            }
        } else {
            cmdAbortDock();
        }
    }

    protected void autopilotAllStop() {
        //kill rotation
        pitch = 0;
        yaw = 0;
        roll = 0;
        //stop throttle
        throttle = 0;
        //get linear velocity
        Vector3f lVol = physics.getLinearVelocity();
        if (lVol.length() > getAcceleration()) {
            //use reverse thrusters
            Vector3f thrustTarget = getSteeringData(getPhysicsLocation().add(lVol), Vector3f.UNIT_Y);
            boolean safe = pointNoseAtVector(thrustTarget, NAV_ANGLE_TOLERANCE);
            if (safe) {
                throttle = -1;
            }
        } else if (lVol.length() > STOP_LOW_VEL_BOUND) {
            //apply counter force
            if (Math.abs(lVol.getX()) > STOP_LOW_VEL_BOUND) {
                float correction = -Math.signum(lVol.getX()) * thrust * STOP_CAUTION;
                if (sufficientFuel(correction)) {
                    physics.applyCentralForce(Vector3f.UNIT_X.mult(correction));
                    useFuel(correction);
                }
            } else {
                lVol = new Vector3f(0, lVol.getY(), lVol.getZ());
                physics.setLinearVelocity(lVol);
            }
            if (Math.abs(lVol.getY()) > STOP_LOW_VEL_BOUND) {
                float correction = -Math.signum(lVol.getY()) * thrust * STOP_CAUTION;
                if (sufficientFuel(correction)) {
                    physics.applyCentralForce(Vector3f.UNIT_Y.mult(correction));
                    useFuel(correction);
                }
            } else {
                lVol = new Vector3f(lVol.getX(), 0, lVol.getZ());
                physics.setLinearVelocity(lVol);
            }
            if (Math.abs(lVol.getZ()) > STOP_LOW_VEL_BOUND) {
                float correction = -Math.signum(lVol.getZ()) * thrust * STOP_CAUTION;
                if (sufficientFuel(correction)) {
                    physics.applyCentralForce(Vector3f.UNIT_Z.mult(correction));
                    useFuel(correction);
                }
            } else {
                lVol = new Vector3f(lVol.getX(), lVol.getY(), 0);
                physics.setLinearVelocity(lVol);
            }
        } else {
            physics.setLinearVelocity(Vector3f.ZERO);
            //we're done
            setAutopilot(Autopilot.NONE);
        }
    }

    private void autopilotFlyToCelestial() {
        if (flyToTarget == null) {
            //abort
            cmdAbort();
        } else {
            if (flyToTarget.getCurrentSystem() != getCurrentSystem()) {
                cmdAbort();
            } else {
                if (flyToTarget.getState() != State.ALIVE) {
                    cmdAbort();
                } else {
                    float dist = flyToTarget.getPhysicsLocation().distance(getPhysicsLocation());
                    if (dist < range) {
                        //stop the ship, we're there
                        cmdAllStop();
                    } else {
                        //determine correct hold to use
                        float hold = 0;
                        if (dist <= getFlightHold()) {
                            hold = dist;
                        } else {
                            hold = getFlightHold();
                        }
                        //move to position
                        moveToPositionWithHold(flyToTarget.getPhysicsLocation(), hold);
                        //detect if autopilot kicked off
                        if (autopilot == Autopilot.NONE) {
                            /*
                             * moveToPosition() detects when the ship has stopped
                             * moving and corrects itself by turning off the autopilot.
                             *
                             * Since we aren't here yet, we need to re-issue the command
                             * to fine tune our approach to the target.
                             */
                            cmdFlyToCelestial(flyToTarget, range);
                        } else {
                            //do nothing, we are still on autopilot
                        }
                    }
                }
            }
        }
    }

    private void moveToPosition(Vector3f end) {
        /*
         * Maintains compatibility with most flight methods.
         */
        moveToPositionWithHold(end, getFlightHold());
    }

    private void moveToPositionWithHold(Vector3f end, float hold) {
        Vector3f b = end.clone();
        //safety
        boolean canAccel = true;
        //see if we are there
        float dist = end.distance(getPhysicsLocation());
        if (dist < hold && hold != Float.POSITIVE_INFINITY) {
            autopilotAllStop();
        } else {
            //get steering to face target
            Vector3f dat = getSteeringData(b, Vector3f.UNIT_Y);
            //make sure we aren't getting further from the target
            Vector3f dPos = physics.getPhysicsLocation().add(physics.getLinearVelocity());
            float stepDistance = dPos.distance(b);
            float distance = physics.getPhysicsLocation().distance(b);
            if (stepDistance > distance) {
                //we are moving further away, all stop
                autopilotAllStop();
            } else {
                canAccel = pointNoseAtVector(dat, NAV_ANGLE_TOLERANCE);
                if (canAccel) {
                    if (physics.getLinearVelocity().length() < hold) {
                        throttle = 1;
                    } else {
                        throttle = 0;
                    }
                } else {
                    throttle = 0;
                    //do nothing
                }
            }
        }
    }

    /*
     * Methods for behaviors in-system
     */
    private void behave() {
        if (behavior == Behavior.NONE) {
        } else if (behavior == Behavior.TEST) {
            behaviorTest();
        }
    }

    protected void behaviorTest() {
        if (autopilot == Autopilot.NONE) {
            //target nearest ship
            targetNearestShip();
            cmdFightTarget(target);
        } else {
            //fighting!
        }
    }

    /*
     * Methods for autopilot out of system
     */
    private void oosAutopilot() {
        if (autopilot == Autopilot.NONE) {
        } else if (autopilot == Autopilot.FLY_TO_CELESTIAL) {
            oosAutopilotFlyToCelestial();
        } else if (autopilot == Autopilot.ALL_STOP) {
            oosAutopilotAllStop();
        } else if (autopilot == Autopilot.DOCK_STAGE1) {
            oosAutopilotDockStageOne();
        } else if (autopilot == Autopilot.DOCK_STAGE2) {
            oosAutopilotDockStageTwo();
        } else if (autopilot == Autopilot.UNDOCK) {
            oosAutopilotUndock();
        } else if (autopilot == Autopilot.ATTACK_TARGET) {
            oosAutopilotFightTarget();
        }
    }

    private void oosAutopilotFightTarget() {
    }

    private void oosAutopilotAllStop() {
        /*
         * OOS objects do not have rotation and behave as points.
         */
        float v = getVelocity().length();
        if (v > OOS_VEL_LOWBOUND) {
            //calculate acceleration in this tick
            float a = getAcceleration() * (float) tpf;
            //check for sufficient fuel for accelerating in this tick
            if (sufficientFuel(getThrust())) {
                //the assumption is that we are rotated the same as the velocity
                Vector3f rot = getVelocity().normalize();
                Vector3f del = rot.mult(-a);
                //apply the acceleration for this tick
                setVelocity(getVelocity().add(del));
                //use fuel
                useFuel(getThrust());
            } else {
                /*
                 * This is less than ideal. There is not enough fuel to finish
                 * the stop. This means the ship is adrift.
                 *
                 * Player ships are set adrift, NPC ships are removed.
                 */
                if (faction.getName().equals(Faction.PLAYER)) {
                    setAutopilot(Autopilot.NONE);
                } else {
                    setState(State.DYING);
                }
            }
        } else {
            //consider this an all stop
            setVelocity(Vector3f.ZERO);
            setAutopilot(Autopilot.NONE);
        }
    }

    private void oosAutopilotFlyToCelestial() {
        if (flyToTarget == null) {
            //abort
            cmdAbort();
        } else {
            if (flyToTarget.getCurrentSystem() != getCurrentSystem()) {
                cmdAbort();
            } else {
                if (flyToTarget.getState() != State.ALIVE) {
                    cmdAbort();
                } else {
                    float dist = flyToTarget.getLocation().distance(getLocation());
                    if (dist < range) {
                        //stop the ship, we're there
                        cmdAllStop();
                    } else {
                        //determine correct hold to use
                        float hold = 0;
                        if (dist <= getFlightHold()) {
                            hold = dist;
                        } else {
                            hold = getFlightHold();
                        }
                        //move to position
                        oosMoveToPositionWithHold(flyToTarget.getLocation(), hold);
                        //detect if autopilot kicked off
                        if (autopilot == Autopilot.NONE) {
                            /*
                             * moveToPosition() detects when the ship has stopped
                             * moving and corrects itself by turning off the autopilot.
                             *
                             * Since we aren't here yet, we need to re-issue the command
                             * to fine tune our approach to the target.
                             */
                            cmdFlyToCelestial(flyToTarget, range);
                        } else {
                            //do nothing, we are still on autopilot
                        }
                    }
                }
            }
        }
    }

    private void oosAutopilotDockStageOne() {
    }

    private void oosAutopilotDockStageTwo() {
    }

    private void oosAutopilotUndock() {
    }

    private void oosMoveToPosition(Vector3f end) {
        oosMoveToPositionWithHold(end, getFlightHold());
    }

    private void oosMoveToPositionWithHold(Vector3f end, float hold) {
        Vector3f b = end.clone();
        //see if we are there
        float dist = end.distance(getLocation());
        if (dist < hold && hold != Float.POSITIVE_INFINITY) {
            autopilotAllStop();
        } else {
            //make sure we aren't getting further from the target
            Vector3f dPos = getLocation().add(getVelocity());
            float stepDistance = dPos.distance(b);
            float distance = getLocation().distance(b);
            if (stepDistance > distance) {
                //we are moving further away, all stop
                oosAutopilotAllStop();
            } else {
                if (getVelocity().length() < hold) {
                    //calculate acceleration in this tick
                    float a = getAcceleration() * (float) tpf;
                    //check for sufficient fuel for accelerating in this tick
                    if (sufficientFuel(getThrust())) {
                        /*
                         * OOS, objects do not have a real rotation. For the
                         * acceleration we will assume the ship is rotated
                         * to face the object.
                         */
                        Vector3f p2 = end.normalize();
                        Vector3f p1 = getLocation().normalize();
                        Vector3f rot = p2.subtract(p1);
                        Vector3f del = rot.mult(a);
                        //apply the acceleration for this tick
                        setVelocity(getVelocity().add(del));
                        //use fuel
                        useFuel(getThrust());
                    }
                } else {
                    //do nothing
                }
            }
        }
    }

    /*
     * Methods for behaviors out of system
     */
    private void oosBehave() {
        if (behavior == Behavior.NONE) {
        } else if (behavior == Behavior.TEST) {
            oosBehaviorTest();
        }
    }

    protected void oosBehaviorTest() {
    }

    /*
     * Methods that can be used no matter what system it is in
     */
    protected void aliveAlways() {
        /*
         * Contains methods to be called no matter if the ship is in system or
         * out of system
         */
        //sync standings
        if (faction.getName().equals(Faction.PLAYER)) {
            if (currentSystem.getUniverse() != null) {
                faction = currentSystem.getUniverse().getPlayerShip().getFaction();
                //messages = getUniverse().getPlayerShip().getMessages();
                //alternateString = true;
            }
        }
        //check docking updates
        if (docked) {
            //no autopilot unless undocking
            if (autopilot != Autopilot.UNDOCK) {
                setAutopilot(Autopilot.NONE);
            }
            //refuel
            fuel = maxFuel;
            //charge shields
            shield = maxShield;
        } else {
            //fire weapons if needed
            if (firing) {
                fireActiveModules();
            }
        }
    }

    protected void updateHealth() {
        //recharge shield
        if (shield < maxShield) {
            shield += (shieldRecharge * tpf);
        }
        //bounds check
        if (shield < 0) {
            shield = 0;
        }
        if (hull <= 0) {
            setState(State.DYING);
        }
    }

    /*
     * Methods for in-system updating. It primarily uses the physics system.
     */
    protected void alive() {
        super.alive();
        aliveAlways();
        //update center
        updateCenter();
        //check health
        updateHealth();
        //update behaviors
        behave();
        //update autopilot
        autopilot();
        //check throttle
        updateThrottle();
        updateTorque();
        updateHardpoints();
        updateNozzles();
        syncPhysics();
    }

    protected void dying() {
        dropExplosion();
        setState(State.DEAD);
    }

    protected void dead() {
        try {
            throw new Exception("Not yet implemented");


        } catch (Exception ex) {
            Logger.getLogger(Ship.class
                    .getName()).log(Level.SEVERE, null, ex);
        }
    }

    protected void updateCenter() {
        if (center == null) {
            center = new Node();
        }
        center.setLocalTranslation(physics.getPhysicsLocation());
    }

    protected void updateThrottle() {
        /*
         * Checks to see if the throttle on the ship is up. If it is, then use
         * the correct engine. Also perform bound checks.
         */
        if (throttle < -1) {
            throttle = -1;
        } else if (throttle > 1) {
            throttle = 1;
        }
        if (throttle > 0) {
            fireRearThrusters(throttle);
        } else if (throttle < 0) {
            fireForwardThrusters(Math.abs(throttle));
        }
        /*
         * Without fuel you won't have any inertial engines so it makes sense
         * to drop to newton mode in that case
         */
        if (fuel <= 0) {
            setEngine(EngineMode.NEWTON);
            physics.setLinearDamping(NEWTON_DAMP);
        } else {
            /*
             * Update the drag coefficient based on whether we are accelerating.
             * When accelerating, drag is applied.
             */
            if (throttle != 0) {
                physics.setLinearDamping(NORMAL_DAMP);
            } else {
                physics.setLinearDamping(NEWTON_DAMP);
            }
        }
    }

    protected void updateTorque() {
        /*
         * Uses the pitch, yaw, and roll targets to rotate the
         * ship.
         */
        if (pitch < -1) {
            pitch = -1;
        } else if (pitch > 1) {
            pitch = 1;
        }
        pitch(pitch);
        if (yaw < -1) {
            yaw = -1;
        } else if (yaw > 1) {
            yaw = 1;
        }
        yaw(yaw);
        if (roll < -1) {
            roll = -1;
        } else if (roll > 1) {
            roll = 1;
        }
        roll(roll);
    }

    protected void updateHardpoints() {
        //update hard points
        for (int a = 0; a < hardpoints.size(); a++) {
            hardpoints.get(a).periodicUpdate(tpf);
        }
    }

    protected void updateNozzles() {
        for (int a = 0; a < nozzles.size(); a++) {
            nozzles.get(a).periodicUpdate(tpf);
        }
    }

    /*
     * Methods for out of system updating, does not use any of the physics system.
     */
    @Override
    protected void oosAlive() {
        super.oosAlive();
        aliveAlways();
        updateHealth();
        oosBehave();
        oosAutopilot();
        //update position
        Vector3f dP = getVelocity().mult((float) tpf);
        setLocation(getLocation().add(dP));
        if (getVelocity().length() > 0) {
            System.out.println(tpf);
        }
    }

    @Override
    protected void oosDying() {
        super.oosDying();
    }

    @Override
    protected void oosDead() {
        super.oosDead();
    }

    /*
     * For applying damage and buffs/debuffs
     */
    public void applyDamage(float damage) {
        /*
         * Some things apply damage uniformly to both shield and
         * hull.
         */
        shield -= damage;
        if (shield < 0) {
            hull += shield;
        }
    }

    public void applyDamage(float shieldDmg, float hullDmg) {
        /*
         * Some weapons will be better against shields than hull. This
         * damage applicator takes that into account.
         */
        shield -= shieldDmg;
        if (shield < 0) {
            hull -= hullDmg;
        }
    }

    /*
     * For applying thrust and torque
     */
    public void roll(float percent) {
        applyTorque(torque * percent, physics.getPhysicsRotation().mult(Vector3f.UNIT_Z));
    }

    public void yaw(float percent) {
        applyTorque(torque * percent, physics.getPhysicsRotation().mult(Vector3f.UNIT_Y));
    }

    public void pitch(float percent) {
        applyTorque(torque * percent, physics.getPhysicsRotation().mult(Vector3f.UNIT_X));
    }

    public void applyTorque(float force, Vector3f axis) {
        if (sufficientFuel(force)) {
            physics.applyTorque(axis.mult(force));
            useFuel(force);
        }
    }

    public void fireRearThrusters(float percent) {
        applyThrust(-getThrust() * percent);
    }

    public void fireForwardThrusters(float percent) {
        applyThrust(getThrust() * percent);
    }

    public void applyThrust(float force) {
        if (engine != EngineMode.NEWTON) {
            if (sufficientFuel(force)) {
                Vector3f direction = physics.getPhysicsRotation().mult(Vector3f.UNIT_Z);
                physics.applyCentralForce(direction.mult(force));
                useFuel(force);
            }
        }
    }

    /*
     * Adding and removing from the scene
     */
    public void attach(Node node, BulletAppState physics, PlanetAppState planetAppState) {
        node.attachChild(center);
        physics.getPhysicsSpace().add(center);
        this.physics.setLinearVelocity(getVelocity().clone());
        this.physics.setPhysicsLocation(getLocation().clone());
        this.physics.setPhysicsRotation(getRotation().clone());
    }

    public void detach(Node node, BulletAppState physics, PlanetAppState planetAppState) {
        setVelocity(this.physics.getLinearVelocity().clone());
        setLocation(this.physics.getPhysicsLocation().clone());
        setRotation(this.physics.getPhysicsRotation().clone());
        node.detachChild(center);
        physics.getPhysicsSpace().remove(center);
    }

    /*
     * For the camera
     */
    public Spatial getSpatial() {
        return spatial;
    }

    /*
     * Access and mutation
     */
    public float getThrust() {
        return thrust;
    }

    public void setThrust(float thrust) {
        this.thrust = thrust;
    }

    public float getShield() {
        return shield;
    }

    public void setShield(float shield) {
        this.shield = shield;
    }

    public float getHull() {
        return hull;
    }

    public void setHull(float hull) {
        this.hull = hull;
    }

    public float getMaxShield() {
        return maxShield;
    }

    public void setMaxShield(float maxShield) {
        this.maxShield = maxShield;
    }

    public float getMaxHull() {
        return maxHull;
    }

    public void setMaxHull(float maxHull) {
        this.maxHull = maxHull;
    }

    public float getFuel() {
        return fuel;
    }

    public void setFuel(float fuel) {
        this.fuel = fuel;
    }

    public float getMaxFuel() {
        return maxFuel;
    }

    public void setMaxFuel(float maxFuel) {
        this.maxFuel = maxFuel;
    }

    public float getSensor() {
        return sensor;
    }

    public void setSensor(float sensor) {
        this.sensor = sensor;
    }

    public float getThrottle() {
        return throttle;
    }

    public void setThrottle(float throttle) {
        this.throttle = throttle;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setRoll(float roll) {
        this.roll = roll;
    }

    public float getShieldRecharge() {
        return shieldRecharge;
    }

    public void setShieldRecharge(float shieldRecharge) {
        this.shieldRecharge = shieldRecharge;
    }

    public Ship getTarget() {
        return target;
    }

    public void setTarget(Ship target) {
        this.target = target;
    }

    /*
     * Utility and reporting
     */
    @Override
    public String toString() {
        String ret = "";
        {
            ret = "(" + getType().getValue("type") + ") - " + getName() + ", " + faction.getName();
        }
        return ret;
    }

    public Term getType() {
        return type;
    }

    public EngineMode getEngine() {
        return engine;
    }

    public void setEngine(EngineMode engine) {
        this.engine = engine;
    }

    private void useFuel(float force) {
        fuel -= Math.abs(force * burnMultiplier) * tpf;
    }

    private boolean sufficientFuel(float force) {
        return fuel - Math.abs(force * burnMultiplier) * tpf >= 0;
    }

    /*
     * Cargo code
     */
    public double getCargo() {
        return cargo;
    }

    public void setCargo(double cargo) {
        this.cargo = cargo;
    }

    public ArrayList<Item> getCargoBay() {
        return cargoBay;
    }

    public boolean addToCargoBay(Item item) {
        if (item != null) {
            /*
             * Puts an item into the cargo bay if there is space available.
             */
            double used = 0;
            for (int a = 0; a < cargoBay.size(); a++) {
                used += cargoBay.get(a).getVolume();
            }
            double fVol = 0;
            if (cargoBay.contains(item)) {
                fVol = item.getVolume() / item.getQuantity();
            } else {
                fVol = item.getVolume();
            }
            if ((cargo - used) > fVol) {
                if (!cargoBay.contains(item)) {
                    cargoBay.add(item);
                } else {
                    item.setQuantity(item.getQuantity() + 1);
                }
            } else {
                return false;
            }
        } else {
            return false;
        }
        return true;
    }

    public void removeFromCargoBay(Item item) {
        if (item.getQuantity() > 1) {
            item.setQuantity(item.getQuantity() - 1);
        } else {
            cargoBay.remove(item);
        }
    }

    public int getNumInCargoBay(Item item) {
        int count = 0;
        if (item != null) {
            String iname = item.getName();
            String itype = item.getType();
            String group = item.getGroup();
            for (int a = 0; a < cargoBay.size(); a++) {
                Item tmp = cargoBay.get(a);
                if (iname.equals(tmp.getName())) {
                    if (itype.equals(tmp.getType())) {
                        if (group.equals(tmp.getGroup())) {
                            count += tmp.getQuantity();
                        }
                    }
                }
            }
        }
        return count;
    }

    public double getBayUsed() {
        double cmass = 0;
        for (int a = 0; a < cargoBay.size(); a++) {
            cmass += cargoBay.get(a).getVolume();
        }
        return cmass;
    }

    public boolean hasInCargo(Item item) {
        return cargoBay.contains(item);
    }

    public boolean hasInCargo(String item) {
        for (int a = 0; a < cargoBay.size(); a++) {
            if (cargoBay.get(a).getName().equals(item)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGroupInCargo(String group) {
        for (int a = 0; a < cargoBay.size(); a++) {
            if (cargoBay.get(a).getGroup().equals(group)) {
                return true;
            }
        }
        return false;
    }

    public void addInitialCargo(String cargo) {
        if (cargo != null) {
            String[] stuff = cargo.split("/");
            for (int a = 0; a < stuff.length; a++) {
                String[] tb = stuff[a].split("~");
                Item tmp = new Item(tb[0]);
                int count = 1;
                if (tb.length == 2) {
                    count = Integer.parseInt(tb[1]);
                }
                for (int v = 0; v < count; v++) {
                    addToCargoBay(tmp);
                }
            }
        }
    }

    /*
     * Docking
     */
    public boolean isDocked() {
        return docked;
    }

    public void setDocked(boolean docked) {
        this.docked = docked;
    }

    public DockingPort getPort() {
        return port;
    }

    /*
     * Cash
     */
    public long getCash() {
        return cash;
    }

    public void setCash(long cash) {
        this.cash = cash;
    }

    /*
     * Hardpoints
     */
    protected void installHardpoints(Term relevant) throws NumberFormatException {
        /*
         * Equips the ship with hardpoints
         */
        String complex = relevant.getValue("hardpoint");
        if (complex != null) {
            String[] arr = complex.split("/");
            for (int a = 0; a < arr.length; a++) {
                String[] re = arr[a].split(",");
                String hType = re[0];
                int hSize = Integer.parseInt(re[1]);
                float hx = Float.parseFloat(re[2]);
                float hy = Float.parseFloat(re[3]);
                float hz = Float.parseFloat(re[4]);
                hardpoints.add(new Hardpoint(this, hType, hSize, new Vector3f(hx, hy, hz)));
            }
        }
    }

    protected void installNozzles(Term relevant) throws NumberFormatException {
        String complex = relevant.getValue("nozzle");
        if (complex != null) {
            String rawStart = relevant.getValue("nozzleStartColor");
            String rawEnd = relevant.getValue("nozzleEndColor");
            String[] arr = complex.split("/");
            for (int a = 0; a < arr.length; a++) {
                String[] re = arr[a].split(",");
                String hType = re[0];
                int hSize = Integer.parseInt(re[1]);
                float hx = Float.parseFloat(re[2]);
                float hy = Float.parseFloat(re[3]);
                float hz = Float.parseFloat(re[4]);
                nozzles.add(new Nozzle(this, hType, hSize, new Vector3f(hx, hy, hz), rawStart, rawEnd));
            }
        }
    }

    /*
     * Fitting
     */
    public void fit(Equipment equipment) {
        for (int a = 0; a < hardpoints.size(); a++) {
            if (equipment.getQuantity() == 1) {
                if (hardpoints.get(a).isEmpty()) {
                    if (hardpoints.get(a).getSize() >= equipment.getVolume()) {
                        if (hardpoints.get(a).getType().equals(equipment.getType())) {
                            hardpoints.get(a).mount(equipment);
                            //is this a weapon?
                            Weapon wep = (Weapon) equipment;
                            //wep.initGraphics();
                            //remove from cargo
                            cargoBay.remove(equipment);
                            break;
                        }
                    }
                }
            }
        }
    }

    public void unfit(Equipment equipment) {
        try {
            for (int a = 0; a < hardpoints.size(); a++) {
                if (hardpoints.get(a).getMounted() == equipment) {
                    if (getBayUsed() + equipment.getVolume() <= cargo) {
                        hardpoints.get(a).unmount(equipment);
                        cargoBay.add(equipment);
                    } else {
                        //not enough room
                    }
                }
            }
        } catch (Exception ex) {
            Logger.getLogger(Ship.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void fireActiveTurrets(Entity target) {
        for (int a = 0; a < hardpoints.size(); a++) {
            if (hardpoints.get(a).getType().equals(Item.TYPE_TURRET) || hardpoints.get(a).getType().equals(Item.TYPE_BATTERY)) {
                hardpoints.get(a).activate(target);
            }
        }
    }

    public void fireActiveGuns(Entity target) {
        for (int a = 0; a < hardpoints.size(); a++) {
            if (hardpoints.get(a).getType().equals(Item.TYPE_CANNON) || hardpoints.get(a).getType().equals(Item.TYPE_MISSILE)) {
                hardpoints.get(a).activate(target);
            }
        }
    }

    public void fireActiveModules() {
        fireActiveTurrets(target);
        fireActiveGuns(target);
    }

    public double getNearWeaponRange() {
        /*
         * Returns the range of the closest range onlined weapon.
         */
        double range = Double.MAX_VALUE;
        for (int a = 0; a < hardpoints.size(); a++) {
            if (hardpoints.get(a).isEnabled()) {
                if (hardpoints.get(a).notNothing()) {
                    if (hardpoints.get(a).getMounted().getRange() < range) {
                        range = hardpoints.get(a).getMounted().getRange();
                    }
                }
            }
        }
        return range;
    }

    public ArrayList<Hardpoint> getHardpoints() {
        return hardpoints;
    }

    /*
     * Faction standings checks
     */
    public int getStandingsToMe(Faction test) {
        return (int) faction.getStanding(test.getName());
    }

    public int getStandingsToMe(Ship ship) {
        if (ship.getFaction().getName().equals(Faction.PLAYER)) {
            return (int) ship.getFaction().getStanding(getFaction().getName());
        } else {
            return (int) faction.getStanding(ship.getFaction().getName());
        }
    }

    public Faction getFaction() {
        return faction;
    }

    public void setFaction(Faction faction) {
        this.faction = faction;
    }

    /*
     * Autopilot commands
     * NOTE: These functions need to remain safe to call whether the ship is
     * in or out of the player's system. This means no access to physics, spatials,
     * or nodes.
     */
    public Autopilot getAutopilot() {
        return autopilot;
    }

    public void setAutopilot(Autopilot autopilot) {
        this.autopilot = autopilot;
    }

    public Behavior getBehavior() {
        return behavior;
    }

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    public void cmdAbort() {
        setAutopilot(Autopilot.NONE);
        if (port != null) {
            port.release();
            port = null;
        }
        throttle = 0;
        pitch = 0;
        yaw = 0;
        roll = 0;
    }

    public void cmdAllStop() {
        setAutopilot(Autopilot.ALL_STOP);
        if (port != null) {
            port.release();
            port = null;
        }
    }

    public void cmdAbortDock() {
        cmdAbort();
        if (port != null) {
            port.release();
            port = null;
        }
    }

    public void cmdDock(Station pick) {
        if (!docked) {
            //TODO: Make this a real behavior
            port = pick.requestDockingPort(this);
            if (port != null) {
                flyToTarget = pick;
                setAutopilot(Autopilot.DOCK_STAGE1);
            }
        }
    }

    public void cmdFightTarget(Ship pick) {
        target = pick;
        setAutopilot(Autopilot.ATTACK_TARGET);
    }

    public void setFlyToTarget(Celestial pick) {
        flyToTarget = pick;
    }

    public Celestial getFlyToTarget() {
        return flyToTarget;
    }

    public void cmdFlyToCelestial(Celestial flyToTarget, float range) {
        setAutopilot(Autopilot.FLY_TO_CELESTIAL);
        if (flyToTarget instanceof Planet) {
            //add radius to range for safety
            Planet tmp = (Planet) flyToTarget;
            range += tmp.getRadius();
        }
        //store range
        setRange(range);
    }

    public void cmdFollowShip(Ship ship, float range) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public void cmdJump(SolarSystem pick) {
        if (canJump(pick)) {
            Random rnd = new Random();
            //drop jump effect
            dropJumpEffect();
            //determine fuel cost
            double fuelCost = getJumpFuelCost(pick);
            //deduct fuel
            fuel -= fuelCost;
            //pull from old system
            currentSystem.pullEntityFromSystem(this);
            //apply negative effects
            float dmg = rnd.nextFloat() * MAX_JUMP_SHIELD_DAMAGE * maxShield;
            applyDamage(dmg);
            //randomize location
            float x = rnd.nextInt(24000 * 2) - 24000;
            float z = rnd.nextInt(24000 * 2) - 24000;
            setLocation(new Vector3f(x, 0, z));
            //put in new system
            pick.putEntityInSystem(this);
            //drop the jump effect
            dropJumpEffect();
        }
    }

    public Station getHomeBase() {
        return homeBase;
    }

    public void setHomeBase(Station homeBase) {
        this.homeBase = homeBase;
    }

    public void cmdUndock() {
        if (docked) {
            setDocked(false);
            setAutopilot(Autopilot.UNDOCK);
        }
    }

    public void clearHomeBase() {
        homeBase = null;
    }

    /*
     * Syncing physics for docking
     */
    public void setPhysicsLocation(Vector3f loc) {
        physics.setPhysicsLocation(loc);
    }

    public void nullVelocity() {
        physics.setLinearVelocity(Vector3f.ZERO);
        physics.setAngularVelocity(Vector3f.ZERO);
    }

    /*
     * Syncing physics for saving
     */
    public void syncPhysics() {
        if (physics != null) {
            setLocation(physics.getPhysicsLocation());
            setRotation(physics.getPhysicsRotation());
            setVelocity(physics.getLinearVelocity());
        }
    }

    /*
     * Range controls for autopilot functions
     */
    public float getRange() {
        return range;
    }

    public void setRange(float range) {
        this.range = range;
    }

    /*
     * Methods to determine "holds" which are AI limits on velocity based
     * on the acceleration of the craft and what the craft is doing
     */
    protected float getFlightHold() {
        return 3 * getAcceleration();
    }

    protected float getFollowHold() {
        return Float.POSITIVE_INFINITY;
    }

    /*
     * Methods to determine physics values
     */
    public float magnitude(float dx, float dy) {
        return (float) Math.sqrt((dx * dx) + (dy * dy));
    }

    public float getAcceleration() {
        return thrust / getMass();
    }

    public float getAngularAcceleration() {
        return torque / getMass();
    }

    public boolean inTolerance(float n1, float n2, float tolerance) {
        return (n1 >= n2 * (1 - tolerance) && n1 <= n2 * (1 + tolerance));
    }

    private Vector3f getSteeringData(Vector3f worldPosition, Vector3f up) {
        // RETREIVE LOCAL DIRECTION TO TARGET POSITION
        Vector3f steeringPosition = new Vector3f();
        getSpatial().getWorldRotation().inverse().multLocal(steeringPosition.set(worldPosition).subtractLocal(getSpatial().getWorldTranslation()));

        // RETREIVE LOCAL UP VECTOR DIRECTION
        Vector3f upPosition = new Vector3f(up);
        getSpatial().getWorldRotation().inverse().multLocal(upPosition);

        // CREATE 2D-VECTORS TO COMPARE
        Vector3f elevatorPos = new Vector3f(steeringPosition).normalizeLocal();
        elevatorPos.x = 0;
        Vector3f rudderPos = new Vector3f(steeringPosition).normalizeLocal();
        rudderPos.y = 0;
        Vector3f aileronPos = new Vector3f(upPosition).normalizeLocal();
        aileronPos.z = 0;

        // CALCULATE ANGLES BETWEEN VECTORS AND INVERT STEERING DIRECTION IF NEEDED
        Vector3f steeringData = new Vector3f();
        steeringData.x = Vector3f.UNIT_Z.angleBetween(elevatorPos);
        if (elevatorPos.y > 0) {
            steeringData.x *= -1;
        }
        steeringData.y = Vector3f.UNIT_Z.angleBetween(rudderPos);
        if (rudderPos.x < 0) {
            steeringData.y *= -1;
        }
        steeringData.z = Vector3f.UNIT_Y.angleBetween(aileronPos);
        if (aileronPos.x > 0) {
            steeringData.z *= -1;
        }

        // RETURN THE DATA
        return steeringData;
    }

    private boolean finePointNoseAtVector(Vector3f dat, float tolerance) {
        boolean canAccel = true;
        //put controls in correct positions to face target
        if (Math.abs(dat.x) < FastMath.PI * (1 - tolerance)) {
            pitch = -(dat.x);
            canAccel = false;
        } else {
            pitch = -(dat.x) / 50.0f;
        }
        if (Math.abs(dat.y) < FastMath.PI * (1 - tolerance)) {
            yaw = -(dat.y);
            canAccel = false;
        } else {
            yaw = -(dat.y) / 50.0f;
        }
        if (Math.abs(dat.z) > FastMath.PI * tolerance) {
            roll = (dat.z);
            //canAccel = false;
        } else {
            //roll = (dat.z) / 100.0f;
            roll = 0;
        }
        return canAccel;
    }

    private boolean pointNoseAtVector(Vector3f dat, float tolerance) {
        boolean canAccel = true;
        //put controls in correct positions to face target
        if (Math.abs(dat.x) < FastMath.PI * (1 - tolerance)) {
            pitch = -(dat.x);
            canAccel = false;
        } else {
            pitch = 0;
        }
        if (Math.abs(dat.y) < FastMath.PI * (1 - tolerance)) {
            yaw = -(dat.y);
            canAccel = false;
        } else {
            yaw = 0;
        }
        if (Math.abs(dat.z) > FastMath.PI * tolerance) {
            roll = (dat.z);
            //canAccel = false;
        } else {
            roll = 0;
        }
        return canAccel;
    }

    /*
     * Used to find groups of objects
     */
    public boolean inSensorRange(Celestial celestial) {
        if (celestial.getLocation().distance(getLocation()) <= sensor) {
            return true;
        } else {
            return false;
        }
    }

    public void targetNearestShip() {
        //get a list of all nearby ships
        ArrayList<Entity> nearby = getCurrentSystem().getShipList();
        ArrayList<Ship> ships = new ArrayList<>();
        for (int a = 0; a < nearby.size(); a++) {
            if (nearby.get(a) instanceof Ship) {
                Ship tmp = (Ship) nearby.get(a);
                if (tmp != this) {
                    //make sure it is alive
                    if (tmp.getState() == State.ALIVE) {
                        //make sure it is in range
                        if (tmp.getLocation().distance(getLocation()) < getSensor()) {
                            ships.add(tmp);
                        }
                    }
                }
            }
        }
        //target the nearest one
        Ship closest = null;
        for (int a = 0; a < ships.size(); a++) {
            if (closest == null) {
                closest = ships.get(a);
            } else {
                double distClosest = closest.getLocation().distance(getLocation());
                double distTest = ships.get(a).getLocation().distance(getLocation());
                if (distTest < distClosest) {
                    closest = ships.get(a);
                }
            }
        }
        //store
        target = closest;
    }

    public ArrayList<Ship> getShipsInSensorRange() {
        ArrayList<Ship> ret = new ArrayList<>();
        {
            //get ship list
            ArrayList<Entity> ships = currentSystem.getShipList();
            for (int a = 0; a < ships.size(); a++) {
                Ship tmp = (Ship) ships.get(a);
                if (tmp != this) {
                    if (tmp.getLocation().distance(getLocation()) < sensor) {
                        ret.add(tmp);
                    }
                }
            }
            //get station list
            ArrayList<Entity> stations = currentSystem.getStationList();
            for (int a = 0; a < stations.size(); a++) {
                Station tmp = (Station) stations.get(a);
                if (tmp.getLocation().distance(getLocation()) < sensor) {
                    ret.add(tmp);
                }
            }
        }
        return ret;
    }

    public ArrayList<Station> getDockableStationsInSystem() {
        return getDockableStationsInSystem(currentSystem);
    }

    public ArrayList<Station> getDockableStationsInSystem(SolarSystem system) {
        ArrayList<Station> list = new ArrayList<>();
        {
            ArrayList<Entity> stations = system.getStationList();
            if (stations.size() > 0) {
                for (int a = 0; a < stations.size(); a++) {
                    Station test = (Station) stations.get(a);
                    if (test.canDock(this)) {
                        list.add(test);
                    }
                }
            }
        }
        return list;
    }

    /*
     * Jump drive
     */
    public boolean canJump(SolarSystem destination) {
        double safety;
        if (behavior == Behavior.UNIVERSE_TRADE) {
            safety = TRADER_JD_SAFETY_FUEL;
        } else {
            safety = JUMP_SAFETY_FUEL;
        }
        //make sure we have a jump drive group device
        if (hasGroupInCargo("jumpdrive")) {
            //fuel cost is linear
            if (fuel - getJumpFuelCost(destination) >= safety * maxFuel) {
                return true;
            }
        }
        return false;
    }

    public double getJumpFuelCost(SolarSystem destination) {
        //calculate distance between current system and destination
        Vector3f cLoc = currentSystem.getLocation();
        Vector3f dLoc = destination.getLocation();
        double dist = cLoc.distance(dLoc);
        //fuel cost is linear
        double fuelCost = dist * 50;
        return fuelCost;
    }

    protected void dropJumpEffect() {
        //TODO
    }

    /*
     * Explosions!
     */
    protected void dropExplosion() {
        Explosion explosion = new Explosion(getCurrentSystem().getUniverse(), 10, getName() + " Explosion");
        explosion.setLocation(getLocation());
        explosion.setRotation(getRotation());
        explosion.setVelocity(getVelocity());
        explosion.setpVel(getVelocity());
        getCurrentSystem().putEntityInSystem(explosion);
    }

    /*
     * For automatically firing shots
     */
    public boolean isFiring() {
        return firing;
    }

    public void setFiring(boolean firing) {
        this.firing = firing;
    }
}
