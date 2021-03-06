package net.michaelripley.toggleshift;

import net.java.games.input.Component.Identifier;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.java.games.input.Event;
import net.java.games.input.EventQueue;
import net.michaelripley.util.UtilFile;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;

/* TODO:
 * crouch fix (see below)
 * hotkey to temporarily pause operation (so i can type)
 * Do I only need to sprint while WASD is being pressed?
 * hotkey to exit the program
 */

public class ToggleShift {
    // JInput Identifiers (used for watching for keypresses)
    private static final Identifier
            IDENTIFIER_SPAMFIRE = Identifier.Button._4,
            IDENTIFIER_TOGGLESPRINT = Identifier.Key.LCONTROL,
            IDENTIFIER_STOPSPRINTING = Identifier.Key.Y,
            IDENTIFIER_FIRE = Identifier.Button.LEFT, // must match KEYCODE_FIRE
            IDENTIFIER_ALTFIRE = Identifier.Button.MIDDLE,
            IDENTIFIER_AIM = Identifier.Button.RIGHT, // must match KEYCODE_AIM
            IDENTIFIER_CROUCH = Identifier.Key.LSHIFT,
            IDENTIFIER_SPINTOWION = Identifier.Key.F9;

    // AWT key codes (used for sending fake keypresses)
    private static final int
            KEYCODE_SPRINT = KeyEvent.VK_CLOSE_BRACKET,
            KEYCODE_FIRE = InputEvent.BUTTON1_DOWN_MASK, // must match IDENTIFIER_FIRE
            KEYCODE_AIM = InputEvent.BUTTON3_DOWN_MASK,  // must match IDENTIFIER_AIM
            KEYCODE_WALK = KeyEvent.VK_W,
            KEYCODE_CROUCH = KeyEvent.VK_SHIFT,
            KEYCODE_MELEE = KeyEvent.VK_E;

    // delays
    private static final long SPRINT_REPEAT_DELAY = 200;
    private static final long FIRE_REPEAT_DELAY = 20;
    private static final long POLLING_DELAY = 20;
    private static final long SPIN_TO_WIN_REPEAT_DELAY = 200;

    private static final String[] LIBRARIES = {
            "jinput-dx8.dll",
            "jinput-dx8_64.dll",
            "jinput-raw.dll",
            "jinput-raw_64.dll",
            "jinput-wintab.dll",
            "libjinput-linux.so",
            "libjinput-linux64.so",
            "libjinput-osx.jnilib"
    };

    /**
     * <code>true</code> if we want to be holding the key
     */
    private volatile boolean desiredSprintSpamState = false;

    private volatile boolean aiming = false;
    private volatile boolean firing = false;
    private volatile boolean altFiring = false;
    private volatile boolean desiredFiringSpamState = false;
    private volatile boolean programRunning = true;
    private volatile boolean crouching = false;
    private volatile boolean mouseOverridden = false;
    private volatile boolean spinToWin = false;

    private final Object sprintSpamLock = new Object();
    private final Object firingSpamLock = new Object();
    private final Object spinToWinLock = new Object();
    private final Robot robot; // Must be initialized in constructor due to Exceptions

    private Controller keyboard;
    private Controller mouse;

    /**
     * Used to differentiate between different instances of the same type of thread
     */
    private static int threadID = 0;

    public static void main(String[] args) {
        try {
            new ToggleShift().run();
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    private ToggleShift() throws AWTException {
        robot = new Robot();
        initializeJInput();
    }

    private void run() {
        new SprintSpamThread("sprint spam thread " + threadID++).start();
        new FiringSpamThread("firing spam thread " + threadID++).start();
        new SpinToWinThread("spin to win thread " + threadID++).start();
        new KeyboardThread("keyboard thread " + threadID++).start();
        new MouseThread("mouse thread " + threadID++).start();
    }


    private void initializeJInput() {
        // copy libraries into workingdir/lib
        final File workingDir = UtilFile.getWorkingDirectory();
        final File libDir = new File(workingDir, "lib");
        try {
            if (!libDir.exists()) {
                if (!libDir.mkdirs()) {
                    // failed to make directory
                    System.err.println("Failed to make library directory");
                    System.exit(1);
                }
            }

            for (final String library : LIBRARIES) {
                final File newFile = new File(libDir, library);
                if (!newFile.exists()) {
                    UtilFile.streamToFile(UtilFile.getFileInputStream(library), newFile);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }


        // set workingdir/lib/ as jinput library path
        System.setProperty("net.java.games.input.librarypath", libDir.getAbsolutePath());

        // if windows, then use directinput explicitly (it doesn't require focus)
        String osName = System.getProperty("os.name", "").trim().toLowerCase();
        if (osName.startsWith("windows")) {
            System.setProperty("net.java.games.input.useDefaultPlugin", "false");
            System.setProperty("net.java.games.input.plugins", "net.java.games.input.DirectInputEnvironmentPlugin");
        }


        // get all keyboards
        final ControllerEnvironment controllerEnvironment = ControllerEnvironment.getDefaultEnvironment();
        final Controller[] controllers = controllerEnvironment.getControllers();

        for (final Controller controller : controllers) {
            if (controller.getType().equals(Controller.Type.KEYBOARD)) {
                if (keyboard == null) {
                    keyboard = controller;
                } else {
                    System.err.println("Multiple keyboards detected");
                    break;
                }
            } else if (controller.getType().equals(Controller.Type.MOUSE)) {
                if (mouse == null) {
                    mouse = controller;
                } else {
                    System.err.println("Multiple mice detected");
                    break;
                }
            }
        }

        if (keyboard == null) {
            System.err.println("No keyboards detected");
        }
    }

    // spins with the intent of winning
    private class SpinToWinThread extends Thread {
        SpinToWinThread(String name) {
            super(name);
        }

        @Override
        public void run() {

            while (programRunning) {
                synchronized (spinToWinLock) {
                    // if we need to not be spinning and therefore losing
                    if (programRunning && (!spinToWin || aiming || firing || altFiring || crouching)) {
                        try {
                            spinToWinLock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }

                robot.keyPress(KEYCODE_WALK);

                // while we need to be spinning to win
                while (programRunning && spinToWin && !aiming && !firing && !altFiring && !crouching) {
                    robot.keyPress(KEYCODE_CROUCH);
                    robot.keyPress(KEYCODE_MELEE);
                    robot.keyRelease(KEYCODE_MELEE);
                    robot.keyRelease(KEYCODE_CROUCH);
                    try {
                        Thread.sleep(SPIN_TO_WIN_REPEAT_DELAY);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }

                spinToWin = false; // force us to toggle it back on

                robot.keyRelease(KEYCODE_WALK);
            }
        }
    }

    // mashes sprint button
    private class SprintSpamThread extends Thread {
        SprintSpamThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            boolean pressed = false;

            while (programRunning) {
                synchronized (sprintSpamLock) {
                    // if we need to not be sprinting
                    if (programRunning && (!desiredSprintSpamState || aiming || firing || altFiring || crouching)) {
                        try {
                            sprintSpamLock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }

                // while we need to be sprinting
                while (programRunning && desiredSprintSpamState && !aiming && !firing && !altFiring && !crouching) {
                    robot.keyRelease(KEYCODE_SPRINT);
                    robot.keyPress(KEYCODE_SPRINT);
                    pressed = true;
                    try {
                        Thread.sleep(SPRINT_REPEAT_DELAY);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }

                // if we simulated a press at some point, release the button
                if (pressed) {
                    robot.keyRelease(KEYCODE_SPRINT);

                    // make sure that we are aiming if we want to be
                    if (aiming) {
                        robot.mousePress(KEYCODE_AIM);
                        mouseOverridden = true;
                    } else if (mouseOverridden) {
                        robot.mouseRelease(KEYCODE_AIM);
                        mouseOverridden = false;
                    }

                    pressed = false;
                }
            }
        }
    }

    // spams fire button
    private class FiringSpamThread extends Thread {
        FiringSpamThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            boolean pressed = false;

            while (programRunning) {
                synchronized (firingSpamLock) {
                    // if we need to not be spamming fire
                    if (programRunning && !desiredFiringSpamState) {
                        try {
                            synchronized (sprintSpamLock) {
                                firing = false;
                                sprintSpamLock.notify(); // wake the thread
                            }

                            firingSpamLock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }

                // while we need to be spamming fire
                while (programRunning && desiredFiringSpamState) {
                    firing = true;
                    robot.mousePress(KEYCODE_FIRE);
                    robot.mouseRelease(KEYCODE_FIRE);
                    pressed = true;
                    try {
                        Thread.sleep(FIRE_REPEAT_DELAY);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }

                // if we simulated a press at some point, release the button
                if (pressed) {
                    robot.mouseRelease(KEYCODE_FIRE);
                    pressed = false;
                }
            }
        }
    }

    // watches keyboard events
    private class KeyboardThread extends Thread {
        KeyboardThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            final EventQueue keyboardEventQueue = keyboard.getEventQueue();
            final Event keyboardEvent = new Event();
            Identifier pressedKey;

            while (programRunning && keyboard.poll()) {
                while (keyboardEventQueue.getNextEvent(keyboardEvent)) {
                    pressedKey = keyboardEvent.getComponent().getIdentifier();

                    // if the toggle key has been pressed
                    if (pressedKey.equals(IDENTIFIER_TOGGLESPRINT) && keyboardEvent.getValue() == 1.0f) {
                        if (desiredSprintSpamState) { // we want to be holding the key currently
                            desiredSprintSpamState = false; // signal our thread to release the key
                        } else { // we are not holding shift currently
                            synchronized (sprintSpamLock) {
                                desiredSprintSpamState = true; // signal our thread to hold the key
                                sprintSpamLock.notify(); // wake the thread
                            }
                        }
                    } else if (pressedKey.equals(IDENTIFIER_CROUCH)) {
                        // if we just pressed crouch
                        if (keyboardEvent.getValue() == 1.0f) {
                            crouching = true;
                        } else {
                            synchronized (sprintSpamLock) {
                                crouching = false;
                                sprintSpamLock.notify(); // wake the thread
                            }
                        }
                    } else if (pressedKey.equals(IDENTIFIER_STOPSPRINTING)) {
                        if (keyboardEvent.getValue() == 1.0f) {
                            desiredSprintSpamState = false; // signal our thread to release the key
                        }
                    } else if (pressedKey.equals(IDENTIFIER_SPINTOWION) && keyboardEvent.getValue() == 1.0f) {
                        if (spinToWin) {
                            spinToWin = false;
                        } else {
                            synchronized (spinToWinLock) {
                                spinToWin = true;
                                spinToWinLock.notify();
                            }
                        }
                    }
                }

                try {
                    Thread.sleep(POLLING_DELAY);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

    // watches mouse events
    private class MouseThread extends Thread {
        MouseThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            final EventQueue mouseEventQueue = mouse.getEventQueue();
            final Event mouseEvent = new Event();
            Identifier pressedButton;

            while (programRunning && mouse.poll()) {
                while (mouseEventQueue.getNextEvent(mouseEvent)) {
                    pressedButton = mouseEvent.getComponent().getIdentifier();

                    // if the aim button has been pressed
                    if (pressedButton.equals(IDENTIFIER_AIM)) {
                        if (mouseEvent.getValue() == 1.0f) { // m2 was just pressed
                            aiming = true;
                        } else { // m2 was just released
                            synchronized (sprintSpamLock) {
                                aiming = false;
                                sprintSpamLock.notify(); // wake the thread
                            }
                        }
                    } else if (pressedButton.equals(IDENTIFIER_ALTFIRE)) {
                        if (mouseEvent.getValue() == 1.0f) { // m3 was just pressed
                            altFiring = true;
                        } else { // m3 was just released
                            synchronized (sprintSpamLock) {
                                altFiring = false;
                                sprintSpamLock.notify(); // wake the thread
                            }
                        }
                    } else if (!desiredFiringSpamState && pressedButton.equals(IDENTIFIER_FIRE)) { // if the fire button has been pressed
                        if (mouseEvent.getValue() == 1.0f) { // m1 was just pressed
                            firing = true;
                        } else { // m1 was just released
                            synchronized (sprintSpamLock) {
                                firing = false;
                                sprintSpamLock.notify(); // wake the thread
                            }
                        }
                    } else if (pressedButton.equals(IDENTIFIER_SPAMFIRE)) { // if the spam fire button has been pressed
                        if (mouseEvent.getValue() == 1.0f) { // m1 was just pressed
                            synchronized (firingSpamLock) {
                                desiredFiringSpamState = true;
                                firingSpamLock.notify(); // wake the thread
                            }
                        } else { // m4 was just released
                            desiredFiringSpamState = false;
                        }
                    }
                }

                try {
                    Thread.sleep(POLLING_DELAY);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }
}
