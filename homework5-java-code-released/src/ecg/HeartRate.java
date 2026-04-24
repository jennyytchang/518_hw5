package ecg;

import dsl.S;
import dsl.Q;
import dsl.Query;

// This file is devoted to the analysis of the heart rate of the patient.
// It is assumed that PeakDetection.qPeaks() has already been implemented.

public class HeartRate {

	private static final double MS_PER_SAMPLE = 1000.0 / 360.0;

	// RR interval length (in milliseconds)
	public static Query<Integer,Double> qIntervals() {
		return Q.pipeline(PeakDetection.qPeaks(), new Query<Long,Double>() {
			private Long previous = null;

			@Override
			public void start(dsl.Sink<Double> sink) {
				previous = null;
			}

			@Override
			public void next(Long item, dsl.Sink<Double> sink) {
				if (previous != null) {
					sink.next((item - previous) * MS_PER_SAMPLE);
				}
				previous = item;
			}

			@Override
			public void end(dsl.Sink<Double> sink) {
				sink.end();
			}
		});
	}

	// Average heart rate (over entire signal) in bpm.
	public static Query<Integer,Double> qHeartRateAvg() {
		return Q.pipeline(qIntervals(), Q.foldAvg(), Q.map(avg -> 60000.0 / avg));
	}

	// Standard deviation of NN interval length (over the entire signal)
	// in milliseconds.
	public static Query<Integer,Double> qSDNN() {
		return Q.pipeline(qIntervals(), new StdDev());
	}

	// RMSSD measure (over the entire signal) in milliseconds.
	public static Query<Integer,Double> qRMSSD() {
		return Q.pipeline(qIntervals(), new Rmssd());
	}

	// Proportion (in %) derived by dividing NN50 by the total number
	// of NN intervals (calculated over the entire signal).
	public static Query<Integer,Double> qPNN50() {
		return Q.pipeline(qIntervals(), new Pnn50());
	}

	private static class StdDev implements Query<Double,Double> {
		private double sum;
		private double sumSq;
		private long count;

		@Override
		public void start(dsl.Sink<Double> sink) {
			sum = 0.0;
			sumSq = 0.0;
			count = 0;
		}

		@Override
		public void next(Double item, dsl.Sink<Double> sink) {
			sum += item;
			sumSq += item * item;
			count += 1;
		}

		@Override
		public void end(dsl.Sink<Double> sink) {
			sink.next(Math.sqrt((sumSq / count) - ((sum / count) * (sum / count))));
			sink.end();
		}
	}

	private static class Rmssd implements Query<Double,Double> {
		private Double previous;
		private double sumSq;
		private long count;

		@Override
		public void start(dsl.Sink<Double> sink) {
			previous = null;
			sumSq = 0.0;
			count = 0;
		}

		@Override
		public void next(Double item, dsl.Sink<Double> sink) {
			if (previous != null) {
				double diff = item - previous;
				sumSq += diff * diff;
				count += 1;
			}
			previous = item;
		}

		@Override
		public void end(dsl.Sink<Double> sink) {
			sink.next(Math.sqrt(sumSq / count));
			sink.end();
		}
	}

	private static class Pnn50 implements Query<Double,Double> {
		private Double previous;
		private long highCount;
		private long totalCount;

		@Override
		public void start(dsl.Sink<Double> sink) {
			previous = null;
			highCount = 0;
			totalCount = 0;
		}

		@Override
		public void next(Double item, dsl.Sink<Double> sink) {
			if (previous != null) {
				totalCount += 1;
				if (Math.abs(item - previous) > 50.0) {
					highCount += 1;
				}
			}
			previous = item;
		}

		@Override
		public void end(dsl.Sink<Double> sink) {
			sink.next((highCount * 100.0) / totalCount);
			sink.end();
		}
	}

	public static void main(String[] args) {
		System.out.println("****************************************");
		System.out.println("***** Algorithm for the Heart Rate *****");
		System.out.println("****************************************");
		System.out.println();

		System.out.println("***** Intervals *****");
		Q.execute(Data.ecgStream("100.csv"), qIntervals(), S.printer());
		System.out.println();

		System.out.println("***** Average heart rate *****");
		Q.execute(Data.ecgStream("100-all.csv"), qHeartRateAvg(), S.printer());
		System.out.println();

		System.out.println("***** HRV Measure: SDNN *****");
		Q.execute(Data.ecgStream("100-all.csv"), qSDNN(), S.printer());
		System.out.println();

		System.out.println("***** HRV Measure: RMSSD *****");
		Q.execute(Data.ecgStream("100-all.csv"), qRMSSD(), S.printer());
		System.out.println();

		System.out.println("***** HRV Measure: pNN50 *****");
		Q.execute(Data.ecgStream("100-all.csv"), qPNN50(), S.printer());
		System.out.println();
	}

}
