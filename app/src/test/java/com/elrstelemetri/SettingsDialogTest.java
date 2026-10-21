package com.elrstelemetri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Number inputs in Settings: "Done" closes the input instead of walking through every other input. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w820dp-h390dp-land")
public class SettingsDialogTest {

    private static void collect(View v, List<EditText> out) {
        if (v instanceof EditText) out.add((EditText) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    @Test
    public void doneKeyFinishesInputWithoutMovingToTheNextOne() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Dialog d = a.showSettings();
        shadowOf(Looper.getMainLooper()).idle();
        List<EditText> inputs = new ArrayList<>();
        collect(d.getWindow().getDecorView(), inputs);
        assertTrue("settings has number inputs", inputs.size() > 5);
        for (EditText e : inputs) {
            assertFalse("no input focused when the screen opens", e.isFocused());
            assertEquals(EditorInfo.IME_ACTION_DONE, e.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        }
        EditText first = inputs.get(0);
        first.requestFocus();
        assertTrue(first.isFocused());
        first.onEditorAction(EditorInfo.IME_ACTION_DONE);
        shadowOf(Looper.getMainLooper()).idle();
        for (EditText e : inputs) assertFalse("focus stays off the inputs after Done", e.isFocused());
        d.dismiss();
    }
}
