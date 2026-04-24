package ecg;

import dsl.Query;
import dsl.Sink;

// The detection algorithm (decision rule) that we described in class
// (or your own slight variant of it).
//
// (1) Determine the threshold using the class TrainModel.
//
// (2) When l[n] exceeds the threhold, search for peak (max x[n] or raw[n])
//     in the next 40 samples.
//
// (3) No peak should be detected for 72 samples after the last peak.
//
// OUTPUT: The timestamp of each peak.

public class Detect implements Query<VTL,Long> {

	// Choose this to be two times the average length
	// over the entire signal.
	private static final double THRESHOLD = 255.30448862892598;

	private static final int SEARCH_WINDOW = 40;
	private static final int REFRACTORY_PERIOD = 72;

	private boolean searching;
	private int samplesInWindow;
	private VTL candidatePeak;
	private long lastPeakTs;

	public Detect() {
		searching = false;
		samplesInWindow = 0;
		candidatePeak = null;
		lastPeakTs = Long.MIN_VALUE / 2;
	}

	@Override
	public void start(Sink<Long> sink) {
		searching = false;
		samplesInWindow = 0;
		candidatePeak = null;
		lastPeakTs = Long.MIN_VALUE / 2;
	}

	@Override
	public void next(VTL item, Sink<Long> sink) {
		if (searching) {
			if (candidatePeak == null || item.v > candidatePeak.v) {
				candidatePeak = item;
			}

			samplesInWindow += 1;
			if (samplesInWindow >= SEARCH_WINDOW) {
				sink.next(candidatePeak.ts);
				lastPeakTs = candidatePeak.ts;
				searching = false;
				samplesInWindow = 0;
				candidatePeak = null;
			}
			return;
		}

		if (item.ts > lastPeakTs + REFRACTORY_PERIOD && item.l > THRESHOLD) {
			searching = true;
			samplesInWindow = 1;
			candidatePeak = item;
		}
	}

	@Override
	public void end(Sink<Long> sink) {
		sink.end();
	}
	
}
