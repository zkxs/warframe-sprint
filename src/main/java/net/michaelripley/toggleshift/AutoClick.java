package net.michaelripley.toggleshift;

import net.java.games.input.Component.Identifier;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.java.games.input.Event;
import net.java.games.input.EventQueue;
import net.michaelripley.util.UtilFile;

import java.awt.*;
import java.awt.event.InputEvent;
import java.io.File;
import java.io.IOException;

public class AutoClick {
    // JInput Identifiers (used for watching for keypresses)
    private final static Identifier IDENTIFIER_SPAMCLICK = Identifier.Button._4;
    private final static Identifier IDENTIFIER_SPAMTOGGLE = Identifier.Key._0;

    // AWT key codes (used for sending fake keypresses)
    private final static int KEYCODE_CLICK = InputEvent.BUTTON1_DOWN_MASK;

    // delays
    private static final long CLICK_REPEAT_DELAY = 5;
    private static final long POLLING_DELAY = 20;

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

    private volatile boolean desiredFiringSpamState = false;
    private volatile boolean programRunning = true;

    private final Object clickSpamLock = new Object();
    private final Robot robot; // Must be initialized in constructor due to Exceptions

    private Controller keyboard;
    private Controller mouse;

    /**
     * Used to differentiate between different instances of the same type of thread
     */
    private static int threadID = 0;

    public static void main(String[] args) {
        try {
            new AutoClick().run();
        } catch (AWTException e) {
            e.printStackTrace();
        }
    }

    public AutoClick() throws AWTException {
        robot = new Robot();
        initializeJInput();
    }

    private void run() {
        new ClickSpamThread("firing spam thread " + threadID++).start();
        new MouseThread("mouse thread " + threadID++).start();
//        new KeyboardThread("keyboard thread " + threadID++).start();
    }


    private void initializeJInput() {
        // copy libraries into workingdir/lib
        File workingDir = UtilFile.getWorkingDirectory();
        File libDir = new File(workingDir, "lib");
        try {
            if (!libDir.exists()) {
                if (!libDir.mkdirs()) {
                    // failed to make directory
                    System.err.println("Failed to make library directory");
                    System.exit(1);
                }
            }

            for (String library : LIBRARIES) {
                File newFile = new File(libDir, library);
                if (!newFile.exists()) UtilFile.streamToFile(UtilFile.getFileInputStream("lib/" + library), newFile);
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
        ControllerEnvironment controllerEnviornment = ControllerEnvironment.getDefaultEnvironment();
        Controller[] controllers = controllerEnviornment.getControllers();

        for (Controller controller : controllers) {
            if (controller.getType().equals(Controller.Type.KEYBOARD)) {
                if (keyboard == null)
                    keyboard = controller;
                else {
                    System.err.println("Multiple keyboards detected");
                    break;
                }
            } else if (controller.getType().equals(Controller.Type.MOUSE)) {
                if (mouse == null)
                    mouse = controller;
                else {
                    System.err.println("Multiple mice detected");
                    break;
                }
            }
        }

        if (keyboard == null) {
            System.err.println("No keyboards detected");
        }
    }

    private class ClickSpamThread extends Thread {
        ClickSpamThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            boolean pressed = false;

            while (programRunning) {
                synchronized (clickSpamLock) {
                    // if we need to not be spamming fire
                    if (programRunning && !desiredFiringSpamState) {
                        try {
                            clickSpamLock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            System.exit(1);
                        }
                    }
                }

                // while we need to be spamming fire
                while (programRunning && desiredFiringSpamState) {
                    robot.mousePress(KEYCODE_CLICK);
                    robot.mouseRelease(KEYCODE_CLICK);
                    pressed = true;
                    try {
                        Thread.sleep(CLICK_REPEAT_DELAY);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }

                // if we simulated a press at some point, release the button
                if (pressed) {
                    robot.mouseRelease(KEYCODE_CLICK);
                    pressed = false;
                }
            }
        }
    }

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

                    // if the spam fire button has been pressed
                    if (pressedButton.equals(IDENTIFIER_SPAMCLICK)) {
                        if (mouseEvent.getValue() == 1.0f) { // m1 was just pressed
                            synchronized (clickSpamLock) {
                                desiredFiringSpamState = true;
                                clickSpamLock.notify(); // wake the thread
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

    private class KeyboardThread extends Thread {
        KeyboardThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            final EventQueue keyboardEventQueue = keyboard.getEventQueue();
            final Event keyboardEvent = new Event();
            Identifier pressedButton;
            boolean lastKeyState = false;

            while (programRunning && keyboard.poll()) {
                while (keyboardEventQueue.getNextEvent(keyboardEvent)) {
                    pressedButton = keyboardEvent.getComponent().getIdentifier();

                    // if the spam fire button has been pressed
                    if (pressedButton.equals(IDENTIFIER_SPAMTOGGLE)) {
                        final boolean keyState = keyboardEvent.getValue() == 1.0f;
                        if (keyState && !lastKeyState) { // key was just pressed
                            synchronized (clickSpamLock) {
                                desiredFiringSpamState = !desiredFiringSpamState;
                                clickSpamLock.notify(); // wake the thread
                            }
                        }
                        lastKeyState = keyState;
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
