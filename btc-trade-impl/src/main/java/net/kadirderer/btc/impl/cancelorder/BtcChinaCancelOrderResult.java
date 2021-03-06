package net.kadirderer.btc.impl.cancelorder;

import net.kadirderer.btc.api.cancelorder.CancelOrderResult;
import net.kadirderer.btc.impl.util.btcc.BtcChinaErrorResult;

public class BtcChinaCancelOrderResult extends CancelOrderResult {

	private boolean result;	
	private String id;
	private BtcChinaErrorResult error;
	
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public boolean getResult() {
		return result;
	}
	
	public void setResult(boolean result) {
		this.result = result;
	}

	public BtcChinaErrorResult getError() {
		return error;
	}

	public void setError(BtcChinaErrorResult error) {
		this.error = error;
	}
	
}
