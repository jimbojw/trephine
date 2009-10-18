package org.trephine;

public class Job {

	public static final Job DONE = new Job( null, null );

	private final String language;
	private final String code;
	
	private Object result;
	private Exception exception;
	private boolean finished = false;
	private boolean success = false;
	
	public boolean getSuccess() {
		return success;
	}

	public boolean isFinished() {
		return finished;
	}

	public Job(String language, String code) {
		this.language = language;
		this.code = code;
		this.result = null;
		this.exception = null;
	}

	public Object getResult() {
		return result;
	}

	public synchronized void setResult(Object result) {
		if ( this.finished )
			throw new RuntimeException( "Job::setResult cannot be called on finished job." );
		this.finished = true;
		this.success = true;
		this.result = result;
		this.notifyAll();
	}

	public Exception getException() {
		return exception;
	}

	public synchronized void setException(Exception exception) {
		if ( this.finished )
			throw new RuntimeException( "Job::setException cannot be called on a finished job." );
		this.finished = true;
		this.success = false;
		this.exception = exception;
		this.notifyAll();
	}

	public String getCode() {
		return code;
	}

	public String getLanguage() {
		return language;
	}
	
}
