package de.hpi.cpn.model;

import java.util.Arrays;

import org.json.JSONException;
import org.json.JSONObject;

import com.thoughtworks.xstream.XStream;

public class CPNTransition extends CPNModellingThing
{
//	VerticalEmptyTransition, Transition
	
	private String explicit = "false";
	private String text;
	private CPNLittleProperty box = CPNLittleProperty.box();
	private CPNLittleProperty binding = CPNLittleProperty.binding();
	private CPNProperty cond = new CPNProperty();
	private CPNProperty time = new CPNProperty();
	private CPNProperty code = new CPNProperty();
	private CPNProperty channel = new CPNProperty();
	
	public CPNTransition()
	{
		super();	
	}
	
	// ---------------------------------------- Mapping ----------------------------------------
	
	public static void registerMapping(XStream xstream)
	{
		xstream.alias("trans", CPNTransition.class);
		
		xstream.useAttributeFor(CPNTransition.class, "explicit");
	}

	// ------------------------------------------- JSON Reader --------------------------------
	
	public void readJSONproperties(JSONObject modelElement) throws JSONException
	{
		JSONObject properties = new JSONObject(modelElement.getString("properties"));
		this.parse(properties);
	}
	
	public void readJSONtitle(JSONObject modelElement) throws JSONException
	{
		String text = modelElement.getString("title");
		
		setText(text);
	}
	
	public void readJSONguard(JSONObject modelElement) throws JSONException
	{
		String guard = modelElement.getString("guard");
		
		JSONObject tempJSON = new JSONObject();		
		tempJSON.put("guard", correctGuard(guard));
		tempJSON.put("id", getId() + "1");
		
		getCond().parse(tempJSON);
	}
	
	public void readJSONbounds(JSONObject modelElement) throws JSONException
	{
		JSONObject boundsJSON = modelElement.getJSONObject("bounds").getJSONObject("upperLeft");
		
		setPositionAttributes(boundsJSON);
		
		setcondPositionAttributes(boundsJSON);
		settimePositionAttributes(boundsJSON);
		setcodePositionAttributes(boundsJSON);
		setchannelPositionAttributes(boundsJSON);
	}
	
	// ------------------------------------------ Helper ----------------------------------------
	
	public static boolean handlesStencil(String stencil)
	{	
		String[] types = {
				"Transition",
				"VerticalEmptyTransition"};
		
		return Arrays.asList(types).contains(stencil);
	}
	
	public String correctGuard(String guardcondition)
	{
		 
		String resultGuardcondition = guardcondition;
		
		// put the guard condition in brackets
		if (! (guardcondition.startsWith("[") && guardcondition.endsWith("]")))
		{
			if (! guardcondition.startsWith("["))
				resultGuardcondition = "[ " + resultGuardcondition;
			if (! guardcondition.endsWith("]"))
				resultGuardcondition =  resultGuardcondition + " ]";
		}
		
		// changing all == to = because CPN Tools cannot understand that
		resultGuardcondition.replace("==", "=");
		
		return resultGuardcondition;
	}
	
	public void setPositionAttributes(JSONObject modelElement) throws JSONException
	{		
		getPosattr().setX(modelElement.getString("x") + ".000000");
		getPosattr().setY(modelElement.getString("y") + ".000000");
	}
	
	public void setcondPositionAttributes(JSONObject modelElement) throws JSONException
	{
		int defaultShiftX = -39;
		int defaultShiftY = 31;
		
		JSONObject condingPositionJSON = new JSONObject();
		
		int x = Integer.parseInt(modelElement.getString("x")) + defaultShiftX;
		int y = Integer.parseInt(modelElement.getString("y")) + defaultShiftY;
		
		condingPositionJSON.put("postattrX", "" + x + ".000000");
		condingPositionJSON.put("postattrY", "" + y + ".000000");
		
		getCond().parse(condingPositionJSON);
	}
	
	public void settimePositionAttributes(JSONObject modelElement) throws JSONException
	{
		int defaultShiftX = 44;
		int defaultShiftY = 33;
		
		JSONObject timePositionJSON = new JSONObject();
		
		int x = Integer.parseInt(modelElement.getString("x")) + defaultShiftX;
		int y = Integer.parseInt(modelElement.getString("y")) + defaultShiftY;
		
		timePositionJSON.put("postattrX", "" + x + ".000000");
		timePositionJSON.put("postattrY", "" + y + ".000000");
		timePositionJSON.put("id", getId() + "2");
		
		getTime().parse(timePositionJSON);
	}
	
	public void setchannelPositionAttributes(JSONObject modelElement) throws JSONException
	{
		int defaultShiftX = 44;
		int defaultShiftY = 33;
		
		JSONObject channelPositionJSON = new JSONObject();
		
		int x = Integer.parseInt(modelElement.getString("x")) + defaultShiftX;
		int y = Integer.parseInt(modelElement.getString("y")) + defaultShiftY;
		
		channelPositionJSON.put("postattrX", "" + x + ".000000");
		channelPositionJSON.put("postattrY", "" + y + ".000000");
		channelPositionJSON.put("id", getId() + "4");
		
		getChannel().parse(channelPositionJSON);
	}
	
	public void setcodePositionAttributes(JSONObject modelElement) throws JSONException
	{
		int defaultShiftX = 44;
		int defaultShiftY = 33;
		
		JSONObject codePositionJSON = new JSONObject();
		
		int x = Integer.parseInt(modelElement.getString("x")) + defaultShiftX;
		int y = Integer.parseInt(modelElement.getString("y")) + defaultShiftY;
		
		codePositionJSON.put("postattrX", "" + x + ".000000");
		codePositionJSON.put("postattrY", "" + y + ".000000");
		codePositionJSON.put("id", getId() + "3");
		
		getCode().parse(codePositionJSON);
	}
	

	// ---------------------------------------- Accessory ----------------------------------------
	public void setText(String text) {
		this.text = text;
	}


	public String getText() {
		return text;
	}

	public void setBox(CPNLittleProperty box) {
		this.box = box;
	}

	public CPNLittleProperty getBox() {
		return box;
	}

	public void setBinding(CPNLittleProperty binding) {
		this.binding = binding;
	}

	public CPNLittleProperty getBinding() {
		return binding;
	}

	public void setCond(CPNProperty cond) {
		this.cond = cond;
	}

	public CPNProperty getCond() {
		return cond;
	}

	public void setTime(CPNProperty time) {
		this.time = time;
	}

	public CPNProperty getTime() {
		return time;
	}

	public void setCode(CPNProperty code) {
		this.code = code;
	}

	public CPNProperty getCode() {
		return code;
	}

	public void setChannel(CPNProperty channel) {
		this.channel = channel;
	}

	public CPNProperty getChannel() {
		return channel;
	}

	public void setExplicit(String explicit) {
		this.explicit = explicit;
	}

	public String getExplicit() {
		return explicit;
	}
}
