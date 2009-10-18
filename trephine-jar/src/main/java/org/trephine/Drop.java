package org.trephine;

public class Drop {

	private Job job = null;

	/**
	 * Wait until a job is available for taking, then return it.
	 * @return A job to execute.
	 */
	public synchronized Job take() {
		while (this.job==null) {
			try {
				this.wait();
			} catch (InterruptedException e) {}
		}
		Job result = this.job;
		this.job = null;
		this.notifyAll();
		return result;
	}
	
	/**
	 * Wait until the job slot is empty, then insert the supplied job.
	 */
	public synchronized void put( Job job ) {
		while (this.job!=null) {
			try { 
				this.wait();
			} catch (InterruptedException e) {}
		}
		this.job = job;
		this.notifyAll();
	}

}

