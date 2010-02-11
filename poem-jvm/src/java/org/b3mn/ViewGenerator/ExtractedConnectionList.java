package org.b3mn.ViewGenerator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;


class ExtractedConnectionList {

	private HashMap<ArrayList<String>, ArrayList<String>> extractedConnectionList; 
	private HashMap<String,String> origins; 
	
	public ExtractedConnectionList() {
		extractedConnectionList = new HashMap<ArrayList<String>, ArrayList<String>>();
		origins = new HashMap<String,String>();
	}

	private void mergeConnectionWithId(String connectionId, ConnectionList connectionList, boolean symmetric, boolean storeRecursive){
		ArrayList<String> extractedConnectionKey1 = new ArrayList<String>();
		ArrayList<String> extractedConnectionKey2 = new ArrayList<String>();
		ConnectionAttributes connectionAttributes = connectionList.getConnectionAttributesFor(connectionId);
		String source = connectionAttributes.getSourceAttribute();
		String target = connectionAttributes.getTargetAttribute();		
		
		extractedConnectionKey1.add(source);
		extractedConnectionKey2.add(target);
		extractedConnectionKey1.add(target);
		extractedConnectionKey2.add(source);
		
		
		if (storeRecursive || (!source.equals(target))) {
			if ((extractedConnectionList.containsKey(extractedConnectionKey1))) {
				ArrayList<String> extractedConnectionValue = extractedConnectionList.get(extractedConnectionKey1);
				if (!extractedConnectionValue.contains(connectionId)) {
					extractedConnectionValue.add(connectionId);
				}
				extractedConnectionList.put(extractedConnectionKey1, extractedConnectionValue);
			}
			
			else if (!extractedConnectionList.containsKey(extractedConnectionKey1) && !symmetric){
				
				ArrayList<String> extractedConnectionValue = new ArrayList<String>();
				extractedConnectionValue.add(connectionId);				
				extractedConnectionList.put(extractedConnectionKey1, extractedConnectionValue);
			}
			
			else if (!extractedConnectionList.containsKey(extractedConnectionKey1) && symmetric){
				
				if (extractedConnectionList.containsKey(extractedConnectionKey2)) {
					
					ArrayList<String> extractedConnectionValue = extractedConnectionList.get(extractedConnectionKey2);
					if (!extractedConnectionValue.contains(connectionId)) {
						extractedConnectionValue.add(connectionId);
					}
					extractedConnectionList.put(extractedConnectionKey2, extractedConnectionValue);
					
				}
				else {
					ArrayList<String> extractedConnectionValue = new ArrayList<String>();
					extractedConnectionValue.add(connectionId);					
					extractedConnectionList.put(extractedConnectionKey1, extractedConnectionValue);
				}			
			}
		}
	}
	
	
	public void merge(ConnectionList connectionList, boolean symmetric, boolean storeRecursive){
		for (String connectionId: connectionList.connectionIds()) {
			origins.put(connectionId, connectionList.getOrigin());
			mergeConnectionWithId(connectionId, connectionList, symmetric, storeRecursive);
		}	
	}
	
	public ArrayList<String> getOriginsForConnectionAttributePair(ArrayList<String> connectionAttributes) {
		ArrayList<String> originsForCon = new ArrayList<String>();
		for (String connectionId: getResourceIdsFor(connectionAttributes)) {
			originsForCon.add(origins.get(connectionId));
		}
		return originsForCon;
	}
	
	public int size(){
		return extractedConnectionList.size();
	}
	
	public void removeConnectionAttributePair(ArrayList<String> connectionAttributes) {
		extractedConnectionList.remove(connectionAttributes);
	}
	
	public ArrayList<String> getResourceIdsFor(ArrayList<String> attributePair) {
		return extractedConnectionList.get(attributePair);
	}
	
	public void putResourceIdsFor(ArrayList<String> resourceIds, ArrayList<String> attributePair) {
		extractedConnectionList.put(attributePair, resourceIds);
	}
	
	public Set<ArrayList<String>> connectionAttributePairs() {
		return extractedConnectionList.keySet();
	}
	
	public boolean containsConnectionAttributePair(ArrayList<String> attributePair) {
		return extractedConnectionList.containsKey(attributePair);
	}	
}