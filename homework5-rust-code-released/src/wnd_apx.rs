use super::*;

const MEM_SIZE: usize = 900; // DO NOT CHANGE

// This file contains the implementation of an approximation algorithm
// for the sliding-window average.
//
// We have to make sure that we do not use more than `MEM_SIZE`
// bytes of memory for the state of our streaming algorithm.


const NUM_BUCKETS: usize = 49;       // B - 1 stored full buckets
const BUCKET_SIZE: usize = WND_SIZE / (NUM_BUCKETS + 1); 

const OFF_CUR_SUM: usize = 2 * NUM_BUCKETS;       
const OFF_CUR_COUNT: usize = OFF_CUR_SUM + 2;    
const OFF_RING_HEAD: usize = OFF_CUR_COUNT + 1;  

// State of the streaming algorithm
pub struct WndApx {
	// DO NOT MAKE ANY CHANGE HERE
	ram: [u8; MEM_SIZE], // memory contents
	// DO NOT MAKE ANY CHANGE HERE
}

impl WndApx {

	pub fn new() -> Self {
		// DO NOT MAKE ANY CHANGE HERE
		Self {
			ram: [0; MEM_SIZE],
		}
		// DO NOT MAKE ANY CHANGE HERE
	}

	fn read_u16(&self, off: usize) -> u16 {
		u16::from_le_bytes([self.ram[off], self.ram[off + 1]])
	}

	fn write_u16(&mut self, off: usize, v: u16) {
		let b = v.to_le_bytes();
		self.ram[off] = b[0];
		self.ram[off + 1] = b[1];
	}

	fn get_bucket(&self, i: usize) -> u16 {
		self.read_u16(2 * i)
	}

	fn set_bucket(&mut self, i: usize, v: u16) {
		self.write_u16(2 * i, v);
	}

	fn current_sum(&self) -> u16 { self.read_u16(OFF_CUR_SUM) }
	fn set_current_sum(&mut self, v: u16) { self.write_u16(OFF_CUR_SUM, v); }

	fn current_count(&self) -> u8 { self.ram[OFF_CUR_COUNT] }
	fn set_current_count(&mut self, v: u8) { self.ram[OFF_CUR_COUNT] = v; }

	fn ring_head(&self) -> u8 { self.ram[OFF_RING_HEAD] }
	fn set_ring_head(&mut self, v: u8) { self.ram[OFF_RING_HEAD] = v; }

}

impl Query for WndApx {

	fn start<S: Sink>(&mut self, _sink: &mut S) {
		self.ram = [0; MEM_SIZE];
	}

	fn next<S: Sink>(&mut self, item: u16, sink: &mut S) {
		assert!(item < LIMIT_SAMPLE);

		let mut cur_sum = self.current_sum();
		let mut cur_count = self.current_count();

		cur_sum += item;
		cur_count += 1;

		if (cur_count as usize) == BUCKET_SIZE {
			let head = self.ring_head() as usize;
			self.set_bucket(head, cur_sum);
			let new_head = (head + 1) % NUM_BUCKETS;
			self.set_ring_head(new_head as u8);
			cur_sum = 0;
			cur_count = 0;
		}

		self.set_current_sum(cur_sum);
		self.set_current_count(cur_count);

		let mut total: u32 = u32::from(cur_sum);
		for i in 0..NUM_BUCKETS {
			total += u32::from(self.get_bucket(i));
		}

		let wnd = WND_SIZE as u32;
		let q = u16::try_from(total / wnd).unwrap();
		let r = u16::try_from(total % wnd).unwrap();
		sink.next((q, r));
	}

	fn end<S: Sink>(&mut self, sink: &mut S) {
		sink.end();
	}

}

// cargo test -- --nocapture --test-threads=1
// cargo test --release -- --nocapture test_wnd_apx_0
// cargo test --release -- --nocapture test_wnd_apx_1
// cargo test --release -- --nocapture test_wnd_apx_2
// cargo test --release -- --nocapture test_wnd_apx_3
#[cfg(test)]
mod tests {
	use super::*;
	use crate::wnd_exact::WndExact;

	#[test]
	fn test_wnd_apx_3() {
		println!("\n");
		println!("***** Approximate Algorithm for Sliding Average *****");
		println!();

		let mut max_rel_error = 0.0_f64;
		for value in 0..LIMIT_SAMPLE {
			let mut sink = sink::SLast::new();
			let mut query = WndExact::new();
			let mut sink_apx = sink::SLast::new();
			let mut query_apx = WndApx::new();
			query.start(&mut sink);
			query_apx.start(&mut sink_apx);

			let it = {
				// constant stream
				core::iter::repeat(value).take(1000).enumerate()
			};
			for (i, item) in it {
				println!("i = {}, item = {}", i, item);
				query.next(item, &mut sink);
				let last = sink.last().unwrap();
				let last = number_u32(last);
				query_apx.next(item, &mut sink_apx);
				let last_apx = sink_apx.last().unwrap();
				let last_apx = number_u32(last_apx);
				let abs_error = last - last_apx;
				println!(
					"  sum: value = {}, estimate = {}, abs. error = {}",
					last, last_apx, abs_error
				);
				assert!(K * abs_error <= last);
				let wnd_size = u16::try_from(WND_SIZE).unwrap();
				let wnd_size = f64::from(wnd_size);
				let last = f64::from(last) / wnd_size;
				let last_apx = f64::from(last_apx) / wnd_size;
				let rel_error = 100.0 * (last - last_apx) / last;
				println!(
					"  avg: value = {:.3}, estimate = {:.3}, rel. error = {:.2}%",
					last, last_apx, rel_error
				);
				if last > 0.0 {
					assert!(rel_error <= EPS_P + 0.000001);
				}
				if !rel_error.is_nan() {
					max_rel_error = max_rel_error.max(rel_error);
				}
				println!();
			}
			query.end(&mut sink);
			query_apx.end(&mut sink_apx);
		}

		println!("maximum relative error = {}", max_rel_error);
		println!();
	}

	#[test]
	fn test_wnd_apx_2() {
		println!("\n");
		println!("***** Approximate Algorithm for Sliding Average *****");
		println!();

		let mut sink = sink::SLast::new();
		let mut query = WndExact::new();
		let mut sink_apx = sink::SLast::new();
		let mut query_apx = WndApx::new();
		query.start(&mut sink);
		query_apx.start(&mut sink_apx);

		let n = 10_000;
		let it = {
			(0..LIMIT_SAMPLE).cycle().take(n).enumerate()
		};
		let mut max_rel_error = 0.0_f64;
		for (i, item) in it {
			println!("i = {}, item = {}", i, item);
			query.next(item, &mut sink);
			let last = sink.last().unwrap();
			let last = number_u32(last);
			query_apx.next(item, &mut sink_apx);
			let last_apx = sink_apx.last().unwrap();
			let last_apx = number_u32(last_apx);
			let abs_error = last - last_apx;
			println!(
				"  sum: value = {}, estimate = {}, abs. error = {}",
				last, last_apx, abs_error
			);
			assert!(K * abs_error <= last);
			let wnd_size = u16::try_from(WND_SIZE).unwrap();
			let wnd_size = f64::from(wnd_size);
			let last = f64::from(last) / wnd_size;
			let last_apx = f64::from(last_apx) / wnd_size;
			let rel_error = 100.0 * (last - last_apx) / last;
			println!(
				"  avg: value = {:.3}, estimate = {:.3}, rel. error = {:.2}%",
				last, last_apx, rel_error
			);
			if last > 0.0 {
				assert!(rel_error <= EPS_P + 0.000001);
			}
			if !rel_error.is_nan() {
				max_rel_error = max_rel_error.max(rel_error);
			}
			println!();
		}
		query.end(&mut sink);
		query_apx.end(&mut sink_apx);

		println!("maximum relative error = {:.2}", max_rel_error);
		println!();
	}

	#[test]
	fn test_wnd_apx_1() {
		println!("\n");
		println!("***** Approximate Algorithm for Sliding Average *****");
		println!();

		// Used in the reference solution for testing individual components
		// of the algorithm.
	}

	#[test]
	fn test_wnd_apx_0() {
		println!("\n");
		println!("***** Approximate Algorithm for Sliding Average *****");
		println!();

		let name = core::any::type_name::<WndApx>();
		let size = core::mem::size_of::<WndApx>();
		assert_eq!(size, MEM_SIZE);
		println!("size of {} = {} bytes", name, size);
		println!();
	}

}
