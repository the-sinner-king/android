package com.cafeed28.omori;

import static android.view.InputDevice.SOURCE_GAMEPAD;
import static android.view.InputDevice.SOURCE_JOYSTICK;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class GameActivity extends Activity {
    private OmoWebView mWebView;
    private Dialog mMenuDialog;
    private Dialog mQuitDialog;

    // NWDECK: physical gamepad button -> OMORI keyboard keycode (Input.keyMapper).
    // WebView maps KEYCODE_BUTTON_* to DOM keyCode 0 ("Unidentified"), so the raw
    // forwarding this class used to do never reached the engine. We translate here,
    // in Java, where the keycode is still known, and hand the engine a synthetic
    // KeyboardEvent with a real keyCode (verified end-to-end: _nwKey path).
    private static final Map<Integer, Integer> GAMEPAD_TO_KEY = new HashMap<>();
    static {
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_A, 90);      // ok (Z)
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_B, 88);      // escape (X)
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_X, 65);      // tag (A)
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_Y, 16);      // shift (dash)
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_L1, 81);     // pageup
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_R1, 87);     // pagedown
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_L2, 81);     // pageup
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_R2, 87);     // pagedown
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_START, 88);  // start = escape
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_BUTTON_SELECT, 9);  // select = tab
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_DPAD_UP, 38);
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_DPAD_DOWN, 40);
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_DPAD_LEFT, 37);
        GAMEPAD_TO_KEY.put(KeyEvent.KEYCODE_DPAD_RIGHT, 39);
    }

    // NWDECK: analog stick -> held arrow-key state (left, right, up, down)
    private final boolean[] mStickState = new boolean[4];
    // NWDECK: analog triggers -> held pageup/pagedown state (0=L2, 1=R2)
    private final boolean[] mTriggerState = new boolean[2];

    private void dispatchKeyToGame(int keyCode, boolean down) {
        if (mWebView == null) return;
        mWebView.eval(
            "(function(k,down){var ev=new KeyboardEvent(down?'keydown':'keyup',{key:String.fromCharCode(k),bubbles:true});" +
            "Object.defineProperty(ev,'keyCode',{get:function(){return k}});" +
            "Object.defineProperty(ev,'which',{get:function(){return k}});" +
            "document.dispatchEvent(ev)})(%d,%b)", keyCode, down);
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // NWDECK: Debug is initialized in MainActivity.onCreate only. When this
        // activity is launched directly (am start .GameActivity), Debug.i() stays
        // null in THIS process and the first console message NPE-kills the app
        // (see OmoWebView.onConsoleMessage / ED-30). Initialize lazily here too.
        if (Debug.i() == null) {
            try {
                new Debug(getFilesDir().getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        setContentView(R.layout.activity_game);

        mWebView = findViewById(R.id.webView);
        mWebView.setOnCloseWindowListener(this::finishAndRemoveTask);

        ViewCompat.setOnApplyWindowInsetsListener(mWebView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();

            // i fucking hate android
            if (insets.left > 0) mlp.leftMargin = insets.left;
            else if (insets.right > 0) mlp.rightMargin = insets.right;

            v.setLayoutParams(mlp);

            return WindowInsetsCompat.CONSUMED;
        });

        mWebView.start();

        final CharSequence[] menuItems = new CharSequence[] {"Toggle FPS counter", "Toggle touch input", "Edit controls", "Quit game"};

        mMenuDialog = new AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(menuItems, (d, w) -> {
                    switch (w) {
                        case 0: // Toggle FPS counter
                            mWebView.eval("Graphics._toggleFPSCounter();");
                            break;
                        case 1: // Toggle touch input
                            mWebView.eval("TouchInput._toggleTouchInput();");
                            break;
                        case 2: // Edit controls
                            mWebView.eval("Input._editControls();");
                            break;
                        case 3: // Quit game
                            mQuitDialog.show();
                            break;
                    }
                    d.dismiss();
                })
                .create();

        mQuitDialog = new AlertDialog.Builder(this)
                .setTitle("Quit game")
                .setMessage("Are you sure you want to quit?")
                .setPositiveButton("Yes", (d, w) -> finishAndRemoveTask())
                .setNegativeButton("No", (d, w) -> d.cancel())
                .create();
    }

    @Override
    protected void onDestroy() {
        if (mWebView != null) {
            try {
                ViewGroup contentView = (ViewGroup) mWebView.getParent();
                contentView.removeView(mWebView);

                // causes "Renderer process crash detected (code -1)"
                // chatgpt says it is normal when we're calling destroy
                // i didn't found any proof of it in documentation or elsewhere
                mWebView.destroy();
                mWebView = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();

        if (mWebView != null) {
            mWebView.onResume();
            mWebView.eval("window.nw.Window.get().dispatchEvent(new Event('restore'));");
        }
    }

    @Override
    protected void onPause() {
        if (mWebView != null) {
            // NWDECK: release every translated key we may have left held. The
            // engine clears its own state on window blur (rpg_core.js
            // Input._onLostFocus), but whether the WebView fires blur on
            // pause is device-dependent — a lost keyup would leave a button
            // pressed forever (auto-confirming menus on wake). Sending keyups
            // for non-held keys is a harmless no-op for the engine.
            for (int mapped : new java.util.TreeSet<>(GAMEPAD_TO_KEY.values())) {
                dispatchKeyToGame(mapped, false);
            }
            releaseStickAndTriggers();
            mWebView.eval("window.nw.Window.get().dispatchEvent(new Event('minimize'));");
            mWebView.onPause();
        }

        super.onPause();
    }

    private void releaseStickAndTriggers() {
        setStickAxis(0, false, 37);
        setStickAxis(1, false, 39);
        setStickAxis(2, false, 38);
        setStickAxis(3, false, 40);
        setTriggerAxis(0, false, 81);
        setTriggerAxis(1, false, 87);
    }

    @Override
    public void onBackPressed() {
        mMenuDialog.show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // NWDECK: diagnostics — log only gamepad-sourced keys (keyboard events
        // include the system's own DPAD/BACK chatter; logging those is noise)
        if (event.isFromSource(SOURCE_GAMEPAD)) {
            Log.i("nwdeck", "dispatchKeyEvent kc=" + event.getKeyCode() + " action=" + event.getAction()
                + " src=0x" + Integer.toHexString(event.getSource()) + " rep=" + event.getRepeatCount());
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    onBackPressed();
                }
            } else {
                Integer mapped = GAMEPAD_TO_KEY.get(keyCode);
                Log.i("nwdeck", "mapped " + keyCode + " -> " + mapped);
                if (mapped != null) {
                    boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                    if (!down || event.getRepeatCount() == 0) {
                        dispatchKeyToGame(mapped, down);
                        Log.i("nwdeck", "dispatchKeyToGame(" + mapped + "," + down + ") sent");
                    }
                } else {
                    // unmapped gamepad key: keep the old raw-forwarding behavior
                    mWebView.dispatchKeyEvent(event);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(SOURCE_JOYSTICK)) {
            // NWDECK: translate analog stick axes into held arrow keys.
            // The engine only sees gamepad state through navigator.getGamepads(),
            // which Android WebView does not implement — so the stick was mute.
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            // hysteresis: engage at |0.6|, release at |0.4| — jittery sticks
            // must not flap the key state (menu flicker / walk stutter)
            setStickAxis(0, x < -0.6f || (mStickState[0] && x < -0.4f), 37); // left
            setStickAxis(1, x >  0.6f || (mStickState[1] && x >  0.4f), 39); // right
            setStickAxis(2, y < -0.6f || (mStickState[2] && y < -0.4f), 38); // up
            setStickAxis(3, y >  0.6f || (mStickState[3] && y >  0.4f), 40); // down
            // NWDECK: analog triggers (this deck reports GAS/BRAKE, not
            // BUTTON_L2/R2 keycodes — without this, L2/R2 are physically mute)
            float gas = event.getAxisValue(MotionEvent.AXIS_GAS);
            float brake = event.getAxisValue(MotionEvent.AXIS_BRAKE);
            setTriggerAxis(0, gas >  0.5f, 81);  // L2 -> pageup
            setTriggerAxis(1, brake > 0.5f, 87); // R2 -> pagedown
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void setStickAxis(int index, boolean active, int keyCode) {
        if (mStickState[index] == active) return;
        mStickState[index] = active;
        dispatchKeyToGame(keyCode, active);
    }

    private void setTriggerAxis(int index, boolean active, int keyCode) {
        if (mTriggerState[index] == active) return;
        mTriggerState[index] = active;
        dispatchKeyToGame(keyCode, active);
    }
}
