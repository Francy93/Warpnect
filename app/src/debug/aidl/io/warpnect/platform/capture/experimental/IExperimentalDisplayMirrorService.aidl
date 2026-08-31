package io.warpnect.platform.capture.experimental;

import android.os.Bundle;

/** Debug-only Shizuku UserService contract for isolated capture compatibility experiments. */
interface IExperimentalDisplayMirrorService {
    Bundle runProbe(int probeKind);
}
