package io.benwiegand.atvremote.receiver.util;

import android.animation.TimeInterpolator;

public class UiUtil {
    public static final TimeInterpolator EASE_OUT = t -> 1-(t-1f)*(t-1f);
    public static final TimeInterpolator EASE_IN = t -> t*t;
    public static final TimeInterpolator EASE_IN_OUT = chainTimeFunctions(EASE_IN, EASE_OUT);
    public static final TimeInterpolator WIND_UP = t -> 2.70158f*t*t*t - 1.70158f*t*t;

    public static TimeInterpolator chainTimeFunctions(TimeInterpolator tf, TimeInterpolator... additionalTfs) {
        for (TimeInterpolator func : additionalTfs) {
            TimeInterpolator prevTf = tf;
            tf = x -> func.getInterpolation(prevTf.getInterpolation(x));
        }
        return tf;
    }

}
