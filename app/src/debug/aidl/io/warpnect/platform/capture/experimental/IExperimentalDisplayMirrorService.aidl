package io.warpnect.platform.capture.experimental;

import android.os.Bundle;
import android.view.Surface;

/** Debug-only Shizuku UserService contract for isolated capture compatibility experiments. */
interface IExperimentalDisplayMirrorService {
    Bundle runProbe(int probeKind);
    Bundle startLegacyMirror(in Surface targetSurface, boolean secure);
    Bundle stopLegacyMirror();
}
