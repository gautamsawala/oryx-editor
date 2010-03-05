package de.hpi.bpmn2yawl;

/**
 * Copyright (c) 2010
 * 
 * @author Armin Zamani
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * s
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import de.hpi.yawl.*;
import de.hpi.yawl.YMultiInstanceParam.CreationMode;
import de.hpi.yawl.resourcing.DistributionSet;
import de.hpi.yawl.resourcing.ResourcingType;
import de.hpi.yawl.resourcing.InitiatorType;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import de.hpi.bpmn.ANDGateway;
import de.hpi.bpmn.Activity;
import de.hpi.bpmn.Assignment;
import de.hpi.bpmn.BPMNDiagram;
import de.hpi.bpmn.Container;
import de.hpi.bpmn.Edge;
import de.hpi.bpmn.EndErrorEvent;
import de.hpi.bpmn.EndPlainEvent;
import de.hpi.bpmn.EndTerminateEvent;
import de.hpi.bpmn.Gateway;
import de.hpi.bpmn.IntermediateErrorEvent;
import de.hpi.bpmn.IntermediateEvent;
import de.hpi.bpmn.IntermediateMessageEvent;
import de.hpi.bpmn.IntermediatePlainEvent;
import de.hpi.bpmn.IntermediateTimerEvent;
import de.hpi.bpmn.Lane;
import de.hpi.bpmn.Node;
import de.hpi.bpmn.ORGateway;
import de.hpi.bpmn.Property;
import de.hpi.bpmn.SequenceFlow;
import de.hpi.bpmn.StartPlainEvent;
import de.hpi.bpmn.SubProcess;
import de.hpi.bpmn.Task;
import de.hpi.bpmn.XORDataBasedGateway;
import de.hpi.bpmn.XOREventBasedGateway;
import de.hpi.bpmn.DataObject;
import de.hpi.bpmn.SequenceFlow.ConditionType;

/**
 * converts a given BPMN diagram to a YAWL diagram
 */
public class BPMN2YAWLConverter {

	/**
	 * counter to give each node a unique name for the YAWL engine
	 */
	private int nodeCount = 0;
	
	/**
	 * hash map for looping activities
	 */
	private HashMap<YDecomposition, LinkedList<Node>> loopingActivities = new HashMap<YDecomposition, LinkedList<Node>>();
	private HashMap<Node, ResourcingType> resourcingNodeMap;

	/**
	 * translates a BPMN Diagram to a YAWL model
	 * @param diagram the BPMN diagram
	 * @param poolIndex index of pool to get the appropiate pool in the diagram
	 * @param resourcingNodeMap node map for YAWL resourcing info
	 * @return the mapped diagram serialized to XML
	 */
	public String translate(BPMNDiagram diagram, int poolIndex, HashMap<Node, ResourcingType> resourcingNodeMap) {
		Container pool = diagram.getProcesses().get(poolIndex);
		YModel model = new YModel("mymodel" + poolIndex);
		model.setDataTypeDefinition(diagram.getDataTypeDefinition());
		this.resourcingNodeMap = resourcingNodeMap;
			
		// YAWL
		mapDecomposition(diagram, model, pool);
			
		return model.writeToYAWL();
	}

	/**
	 * mappes pools or subprocesses to decompositions (contain net information)
	 * @param diagram the BPMN diagram
	 * @param model the YAWL model to which the decomposition is added 
	 * @param graph the BPMN element that should be mapped to a decomposition
	 * @return the mapped decomposition
	 */
	private YDecomposition mapDecomposition(BPMNDiagram diagram, YModel model, Container graph) {

		YDecomposition dec = null;
		HashMap<Node, YNode> nodeMap = new HashMap<Node, YNode>();
		LinkedList<Node> gateways = new LinkedList<Node>();
		LinkedList<Activity> withEventHandlers = new LinkedList<Activity>();
		LinkedList<EndTerminateEvent> terminateEvents = new LinkedList<EndTerminateEvent>();
		
		//check for Subprocess
		if (graph instanceof SubProcess) {
			String subProcessLabel = ((SubProcess)graph).getLabel().replace(" ", "");
			dec = model.createDecomposition(generateId(subProcessLabel));
		} else{
			//if it is not a Subprocess, it is the main decomposition
			dec = model.createDecomposition("OryxBPMNtoYAWL_Net");
			dec.setRootNet(true);
		}
		
		// Map process elements
		for (Node node : graph.getChildNodes()) {
			if (node instanceof Activity && ((Activity)node).getAttachedEvents().size() > 0) {
				withEventHandlers.add((Activity)node);
			}
			
			if(node instanceof IntermediateEvent){
				if(((IntermediateEvent) node).isAttached())
					continue;
			}
			
			YNode ynode = mapProcessElement(diagram, model, dec, node, nodeMap);
			if ((ynode == null) && (node instanceof Gateway))
				gateways.add(node);
		}		
		
		// Map gateways
		for (Node node : gateways){
			mapGateway(model, dec, node, nodeMap);
		}
		
		//check decomposition's input and output condition
		if (dec.getOutputCondition() == null)
			dec.createOutputCondition(generateId("Output"), "Output Condition");
		
		if (dec.getInputCondition() == null){
			dec.createInputCondition(generateId("Input"), "Input Condition");
			if(nodeMap.isEmpty())
				dec.connectInputToOutput();	
		}
		
		// Map data objects
		for (DataObject dataObject : diagram.getDataObjects()) {
			mapDataObject(model, dec, dataObject, nodeMap);
		}
		
		// Map links and edges
		linkYawlElements(nodeMap, dec, terminateEvents);

		// Event handlers
		for (Activity act : withEventHandlers)
			mapExceptions(model, dec, act, nodeMap);
		
		rewriteLoopingTasks(nodeMap);
		
		for(EndTerminateEvent terminate : terminateEvents){
			YNode sourceTask = nodeMap.get(terminate);
			mapEndTerminateToCancellationSet(sourceTask, nodeMap);
		}
		
		return dec;
	}

	/**
	 * Graph rewriting to deal with looping activities
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 */
	private void rewriteLoopingTasks(HashMap<Node, YNode> nodeMap) {
		for (YDecomposition decomposition : loopingActivities.keySet()) {
			LinkedList<Node> activities = loopingActivities.get(decomposition);
			for (Node activityNode : activities) {
				YTask task = (YTask)nodeMap.get(activityNode);
				
				//add a splitting task and seperate the task from split rules, if it is an AND split
				if (task.getSplitType() == SplitJoinType.AND) {
					// Factor out the split decorator to allow a self loop
					YTask split = decomposition.createTask(generateId(), "SplitTask");

					for (YEdge edge : task.getOutgoingEdges())
						decomposition.createEdge(split, edge.getTarget(), false, "", 0);

					task.getOutgoingEdges().clear();
					
					decomposition.createEdge(task, split, false, "", 0);					
				}

				//add a joining task and seperate the task from joining rules, if it is an AND join
				if (task.getJoinType() == SplitJoinType.AND) {
					// Factor out the split decorator to allow a self loop
					YTask join = decomposition.createTask(generateId(), "JoinTask", 
							SplitJoinType.AND, SplitJoinType.AND);

					for (YEdge edge : task.getIncomingEdges())
							decomposition.createEdge(edge.getSource(), join, false, "", 0);
						
					task.getIncomingEdges().clear();
					
					decomposition.createEdge(join, task, false, "", 0);					
				}
				Activity activity = (Activity)activityNode;
				String predicate = "";
				if(activity.getLoopType() == Activity.LoopType.Standard)
					predicate = activity.getLoopCondition();
				else if (isLoopingActivityBySequenceFlow(activityNode))
					predicate = getExpressionForLoopingActivityBySequenceFlow(activityNode);

				// Self loop edge
				decomposition.createEdge(task, task, false, predicate, 1);
				task.setSplitType(SplitJoinType.XOR);
				task.setJoinType(SplitJoinType.XOR);
			}
		}
	}

	/**
	 * mappes BPMN exceptions - events attached to activities - to YAWL
	 * @param model containing model
	 * @param decomposition containing decomposition
	 * @param activity activity that has attached events
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 */
	private void mapExceptions(YModel model, YDecomposition decomposition, Activity activity,
			HashMap<Node, YNode> nodeMap) {
		YTask compositeTask = (YTask) nodeMap.get(activity);
		YTask sourceTask = compositeTask;
		boolean splitAttached = false;
		LinkedList<IntermediateTimerEvent> timers = new LinkedList<IntermediateTimerEvent>();
		
		//seperate between timer and other attached events
		for (IntermediateEvent eventHandler : activity.getAttachedEvents()) {
			if (eventHandler instanceof IntermediateTimerEvent)
				timers.add((IntermediateTimerEvent)eventHandler);
			else
				splitAttached = true;
		}
		
		if (splitAttached) {
			if (compositeTask.getOutgoingEdges().size() > 1) {
				//create a new splitting task after composite task
				YTask newSplit = decomposition.createTask(generateId(), "newSplitTask");
				
				for (YEdge edge : compositeTask.getOutgoingEdges())
					decomposition.createEdge(newSplit, edge.getTarget(), false, "", 0);
					
				compositeTask.getOutgoingEdges().clear();
				
				decomposition.createEdge(compositeTask, newSplit, false, "", 0);
				sourceTask = newSplit;
				newSplit.setSplitType(compositeTask.getSplitType());
			}
			compositeTask.setSplitType(SplitJoinType.XOR);
		}
		
		for (IntermediateEvent eventHandler : activity.getAttachedEvents()) {
			if (eventHandler instanceof IntermediateErrorEvent) {
				mapErrorException(model, decomposition, nodeMap, compositeTask, sourceTask,
						(IntermediateErrorEvent) eventHandler);
			} else if (eventHandler instanceof IntermediateTimerEvent) {
				mapTimerException(model, decomposition, nodeMap, compositeTask, sourceTask,
						(IntermediateTimerEvent)eventHandler, timers);				
			}
		}
	}

	/**
	 * mappes attached Intermediate Timer Events as Timer Exceptions with the composite task in the cancellation set of the mapped timer task
	 * and the timer task in the cancellation set of the composite task
	 * @param model containing model
	 * @param decomposition containing decomposition
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 * @param compositeTask the composite task
	 * @param sourceTask the source task
	 * @param eventHandler the BPMN event
	 * @param timers linked list of BPMN Intermediate Timer Events attached to an activity
	 */
	private void mapTimerException(YModel model, YDecomposition decomposition,
			HashMap<Node, YNode> nodeMap, YTask compositeTask, YTask sourceTask,
			IntermediateTimerEvent eventHandler, LinkedList<IntermediateTimerEvent> timers) {
		
		YTask timerEventTask = (YTask)mapTimerEvent(model, decomposition, eventHandler, nodeMap, true);
		YNode targetTask = nodeMap.get(eventHandler.getOutgoingSequenceFlows().get(0).getTarget());
		decomposition.createEdge(timerEventTask, targetTask, false, "", 1);
		
		if (timers.size() == 0) 
			return;

		YNode predecesor = null;
		boolean needsLinking = false;
		if (compositeTask.getIncomingEdges().size() > 1) {
			predecesor = decomposition.createTask(generateId(), "Task");
			Task predecesorTask = new Task();
			nodeMap.put(predecesorTask, predecesor);
			needsLinking = true;
		} else {
			predecesor = (YNode)compositeTask.getIncomingEdges().get(0).getSource();

			if (predecesor instanceof YCondition) {
				YNode gw = decomposition.createTask(generateId(), "Task");

				YEdge edge = (YEdge) predecesor.getOutgoingEdges().get(0);
				decomposition.removeEdge(edge);

				decomposition.createEdge(predecesor, gw, false, "", 1);
				predecesor = gw;
				Task predecesorTask = new Task();
				nodeMap.put(predecesorTask, predecesor);
				needsLinking = true;
			} else if ((predecesor.getOutgoingEdges().size() > 1 && ((YTask)predecesor).getSplitType() != SplitJoinType.AND)) {
				; // TODO: factor out a AND split
			}
		}

		for (IntermediateTimerEvent timer : timers) {
			YTask timerTask = (YTask)nodeMap.get(timer);
			compositeTask.getCancellationSet().add(timerTask);
			timerTask.getCancellationSet().add(compositeTask);
			for (IntermediateTimerEvent another : timers) {
				if (!timer.equals(another))
					timerTask.getCancellationSet().add((YTask)nodeMap.get(another));
			}

			decomposition.createEdge(predecesor, timerTask, false, "", 1);
		}

		if (needsLinking) {
			decomposition.createEdge(predecesor, compositeTask, false, "", 1);
		}
	}

	/**
	 * mappes an Intermediate Error event to an exception task that sets the exception variable to true
	 * if executed
	 * @param model containing model
	 * @param decomposition containing decomposition
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 * @param compositeTask the composite task
	 * @param sourceTask the source task
	 * @param eventHandler the BPMN event (Intermediate Error Event)
	 */
	private void mapErrorException(YModel model, YDecomposition decomposition,
			HashMap<Node, YNode> nodeMap, YTask compositeTask, YTask sourceTask,
			IntermediateErrorEvent eventHandler) {
		
		YNode targetTask = nodeMap.get(eventHandler.getOutgoingSequenceFlows().get(0).getTarget());

		// PREDICATE & Mapping
		String varName =  sourceTask.getID();
		String predicate = String.format("/%s/%s_%s_exception/text()", decomposition.getID(), compositeTask.getID(), varName);
		String tag = String.format("%s_%s_exception", compositeTask.getID(), varName);
		String query = String.format("&lt;%s&gt;{%s}&lt;/%s&gt;", tag, predicate, tag);

		//TODO: defaultFlow!!!
		if(!sourceTask.getOutgoingEdges().isEmpty()){
			int edgeCounter = 2;
			for(YEdge edge : sourceTask.getOutgoingEdges()){
				if(edge.getOrdering() == 0){
					edge.setOrdering(edgeCounter++);
				}
				if(edge.getPredicate().isEmpty()){
					edge.setDefault(true);
				}
			}
		}
		decomposition.createEdge(sourceTask, targetTask, false, predicate, 1);

		//create the exception variable
		YVariable local = new YVariable();
		local.setName(tag);
		local.setType("boolean");
		local.setInitialValue("false");
		decomposition.getLocalVariables().add(local);

		YVariableMapping mapping = new YVariableMapping(query, local);

		compositeTask.getCompletedMappings().add(mapping);
		
		// Add control flow variables to composite task decomposition
		YVariable localVariable = new YVariable();
		localVariable.setName("_"+varName+"_exception");
		localVariable.setType("boolean");
		compositeTask.getDecomposesTo().getLocalVariables().add(localVariable);
		
		YVariable outputParam = new YVariable();
		outputParam.setName("_"+varName+"_exception");
		outputParam.setType("boolean");
		compositeTask.getDecomposesTo().getOutputParams().add(outputParam);
		
		for (YNode exceptionNode : compositeTask.getDecomposesTo().getNodes()) {
			if(exceptionNode instanceof YTask){
				YTask exceptionTask = (YTask)exceptionNode;
				
				if (exceptionTask.getID().contains("ErrorEvent")) {
					//variable mapping to set the exception variable to true
					String anotherQuery = String.format("&lt;%s&gt;true&lt;/%s&gt;", localVariable.getName(), localVariable.getName());
					YVariableMapping anotherMapping = new YVariableMapping(anotherQuery, localVariable);

					exceptionTask.getCompletedMappings().add(anotherMapping);
					
					// create an executable decomposition
					YDecomposition exceptionDec = null;
					exceptionDec = setTaskDecomposition(model, exceptionDec, exceptionTask);
					break;
				}
			}
		}
	}

	/**
	 * mappes AND, OR, Data Xor gateways to a splitting or joining task or adds a splitting
	 * or joining decorator to the predecessor or successor task
	 * first the task that gets the decorators is determined, then the decorators are added to task
	 * @param model containing model
	 * @param decomposition containing decomposition
	 * @param node BPMN node to be mapped
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 */
	private void mapGateway(YModel model, YDecomposition decomposition, Node node,
			HashMap<Node, YNode> nodeMap) {
		YTask task = null;
		boolean split = false, join = false;
		//determine to which task the decorator should be added
		if (node.getOutgoingSequenceFlows().size() > 1 && node.getIncomingSequenceFlows().size() > 1) {
			// Split and Join roles
			task = decomposition.createTask(generateId(), "Task");

			split = true; join = true;
		} else if (node.getOutgoingSequenceFlows().size() > 1) {
			// SPLIT role
			Node predNode = (Node) node.getIncomingSequenceFlows().get(0).getSource();
			YNode predTask = nodeMap.get(predNode);
			
			if (predTask == null || (predNode.getOutgoingSequenceFlows() != null && predNode.getOutgoingSequenceFlows().size() > 1) ||
					predTask instanceof YCondition)
				task = decomposition.createTask(generateId(), "Task");
			else
				task = (YTask)predTask;
			split = true;
		} else if (node.getIncomingSequenceFlows().size() > 1) {
			// JOIN role			
			Node succNode = (Node) node.getOutgoingSequenceFlows().get(0).getTarget();
			YNode succTask = nodeMap.get(succNode);
			
			if (succTask == null || (succNode.getIncomingSequenceFlows() != null && succNode.getIncomingSequenceFlows().size() > 1) ||
					succTask instanceof YCondition)
				task = decomposition.createTask(generateId(), "Task");
			else
				task = (YTask)succTask;
			join = true;
		}
		//set the split and join type of task
		if (node instanceof XORDataBasedGateway) {
			if (split)
				task.setSplitType(SplitJoinType.XOR);
			if (join)
				task.setJoinType(SplitJoinType.XOR);
		} else if (node instanceof ANDGateway) {
			if (split)
				task.setSplitType(SplitJoinType.AND);
			if (join)
				task.setJoinType(SplitJoinType.AND);
		} else if (node instanceof ORGateway) {
			if (split)
				task.setSplitType(SplitJoinType.OR);
			if (join)
				task.setJoinType(SplitJoinType.OR);
		}
		
		nodeMap.put(node, task);
	}
	
	/**
	 * determines if the given node is a looping node through control flow
	 * the node is looping, if the third successor node of the given node is itself
	 * the nodes between the given and the third successor node may only be AND, OR or
	 * Data-based Xor Gateways
	 * @param node the node to be tested if it is looping
	 * @return is node looping?
	 */
	private boolean isLoopingActivityBySequenceFlow(Node node){
		
		for(SequenceFlow firstSequenceFlow: node.getOutgoingSequenceFlows()){
			Node firstSuccessorNode = (Node) firstSequenceFlow.getTarget();
			if((firstSuccessorNode instanceof ANDGateway) || (firstSuccessorNode instanceof ORGateway) || (firstSuccessorNode instanceof XORDataBasedGateway)){
				for(SequenceFlow secondSequenceFlow: firstSuccessorNode.getOutgoingSequenceFlows()){
					Node secondSuccessorNode = (Node) secondSequenceFlow.getTarget();
					if((secondSuccessorNode instanceof ANDGateway) || (secondSuccessorNode instanceof ORGateway) || (secondSuccessorNode instanceof XORDataBasedGateway)){
						for(SequenceFlow thirdSequenceFlow: secondSuccessorNode.getOutgoingSequenceFlows()){
							Node thirdSuccessorNode = (Node) thirdSequenceFlow.getTarget();
							
							if(thirdSuccessorNode == node)
								return true;
						}
					}
				}
			}
		}	
		return false;
	}

	/**
	 * returns the condition expression of the outgoing sequence flow of the splitting gateway
	 * if the node is looping by control flow (see above)
	 * @param node the looping node
	 * @return the condition expression of the outgoing sequence flow of the splitting gateway
	 */
	private String getExpressionForLoopingActivityBySequenceFlow(Node node){
		String result = "";
		
		for(SequenceFlow firstSequenceFlow: node.getOutgoingSequenceFlows()){
			Node firstSuccessorNode = (Node) firstSequenceFlow.getTarget();
			if((firstSuccessorNode instanceof ANDGateway) || (firstSuccessorNode instanceof ORGateway) || (firstSuccessorNode instanceof XORDataBasedGateway)){
				for(SequenceFlow secondSequenceFlow: firstSuccessorNode.getOutgoingSequenceFlows()){
					if(secondSequenceFlow.getConditionType() == SequenceFlow.ConditionType.EXPRESSION){
						Node secondSuccessorNode = (Node) secondSequenceFlow.getTarget();
						if((secondSuccessorNode instanceof ANDGateway) || (secondSuccessorNode instanceof ORGateway) || (secondSuccessorNode instanceof XORDataBasedGateway)){
							for(SequenceFlow thirdSequenceFlow: secondSuccessorNode.getOutgoingSequenceFlows()){
								Node thirdSuccessorNode = (Node) thirdSequenceFlow.getTarget();
							
								if(thirdSuccessorNode == node)
									return secondSequenceFlow.getConditionExpression();
							}
						}
					}
				}
			}
		}
		return result;
	}
	
	/**
	 * generates a unique id
	 * @return generated unique id
	 */
	private String generateId() {
		return generateId("gw");
	}
	
	/**
	 * generates a unique node id for the YAWL engine
	 * @param infix String that describes the node and becomes part of the name
	 * @return unique identifier
	 */
	private String generateId(String infix) {
		return "Node_" + infix + "_" + (nodeCount++);
	}

	/**
	 * mappes the given BPMN node to the according YAWL node
	 * Subprocess -> Composite Task
	 * Activity -> Task
	 * Start Plain Event -> Input Condition
	 * End Plain Event -> Output Condition
	 * XOR Event BasedGateway -> Condition
	 * Intermediate Timer Event -> Timer task
	 * Intermediate Message Event (predecessor node is an Event-based Gateway) -> Task
	 * Intermediate Event (predecessor node is a Gateway) -> Task
	 * Intermediate Plain Event -> Condition
	 * End Error Event -> Task
	 * End Terminate Event -> Task
	 * @param diagram the BPMN diagram
	 * @param model containing model
	 * @param node the BPMN node to be mapped
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 * @return the mapped YAWL node
	 */
	private YNode mapProcessElement(BPMNDiagram diagram, YModel model, YDecomposition dec, Node node, HashMap<Node, YNode> nodeMap) {
		YNode ynode = null;
		
		if (node instanceof SubProcess)
			ynode = mapCompositeTask(diagram, model, dec, (Activity)node, nodeMap);
		
		else if (node instanceof Activity)
			ynode = mapTask(model, dec, (Activity)node, nodeMap);
		
		else if (node instanceof StartPlainEvent)
			ynode = dec.createInputCondition(generateId("input"), "inputCondition");
		
		else if (node instanceof EndPlainEvent)
			ynode = dec.createOutputCondition(generateId("output"), "outputCondition");

		else if (node instanceof XOREventBasedGateway)
			ynode = mapConditionFromEventBased(model, dec, node, nodeMap);
		
		else if (node instanceof IntermediateTimerEvent)
			ynode = mapTimerEvent(model, dec, node, nodeMap, false);
		
		else if (node instanceof IntermediateMessageEvent && node.getIncomingSequenceFlows().get(0).getSource() instanceof XOREventBasedGateway)
			ynode = mapIntermediateMessageEvent(model, dec, node, nodeMap);
		
		else if (node instanceof IntermediateEvent && node.getIncomingSequenceFlows().get(0).getSource() instanceof Gateway)
			ynode = mapIntermediateEvent(model, dec, node, nodeMap);
		
		else if (node instanceof IntermediatePlainEvent && node.getOutgoingSequenceFlows().get(0).getTarget() instanceof Gateway) 
			ynode = dec.createCondition(generateId("plain"), "ConditionMappedFromIntermediatePlainEvent");
		
		else if (node instanceof EndErrorEvent)
			ynode = dec.createTask(generateId("ErrorEvent"), "TaskMappedFromErrorEvent");

		else if (node instanceof EndTerminateEvent)
			ynode = dec.createTask(generateId("endTerminate"), "CancellationTask");
		
		if (ynode != null)
			nodeMap.put(node, ynode);
			
		return ynode;
	}

	/**
	 * mappes data objects to YAWL variables and defines the task parameters according to
	 * associations
	 * @param model containing model
	 * @param decomposition containing decomposition
	 * @param dataObject the data object to be mapped
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 */
	private void mapDataObject(YModel model, YDecomposition decomposition, DataObject dataObject,
			HashMap<Node, YNode> nodeMap) {
		if((dataObject.getIncomingEdges().size() == 0) && (dataObject.getOutgoingEdges().size() == 0))
			return;
		
		//check, if the new variable name is used already
		//TODO: Take it out, if a dataSyntaxChecker exists
		for(YVariable variable : decomposition.getLocalVariables()){
			if(variable.getName().equalsIgnoreCase(dataObject.getLabel()))
				return;
		}
		
		//define the local variable and add it to decomposition
		YVariable localVar = new YVariable();
		localVar.setName(dataObject.getLabel());
		localVar.setType(dataObject.getDataType());
		localVar.setInitialValue(dataObject.getValue());
		decomposition.getLocalVariables().add(localVar);
		
		//set the input parameters for nodes that the data object references
		for (Edge edge : dataObject.getOutgoingEdges()){
			Node targetNode = (Node)edge.getTarget();
			YTask targetTask = (YTask) nodeMap.get(targetNode);
			
			String startQuery = "&lt;" + localVar.getName() + "&gt;{/" + decomposition.getID() + "/" + localVar.getName() + "/text()}&lt;/" + localVar.getName() +"&gt;";
			YVariableMapping localVarMapping = new YVariableMapping(startQuery, localVar);
			targetTask.getStartingMappings().add(localVarMapping);
			if(targetTask.getDecomposesTo() != null){
				targetTask.getDecomposesTo().getInputParams().add(localVar);
			}
		}
		
		//set the output parameters for nodes that reference the data object
		for (Edge edge : dataObject.getIncomingEdges()){
			Node sourceNode = (Node)edge.getSource();
			YTask sourceTask = (YTask) nodeMap.get(sourceNode);
			
			String completeQuery = "&lt;" + localVar.getName() + "&gt;{/" + sourceTask.getID() + "/" + localVar.getName() + "/text()}&lt;/" + localVar.getName() +"&gt;";
			YVariableMapping localVarMapping = new YVariableMapping(completeQuery, localVar);
			sourceTask.getCompletedMappings().add(localVarMapping);
			if(sourceTask.getDecomposesTo() != null){
				sourceTask.getDecomposesTo().getOutputParams().add(localVar);
			}
		}
		
	}

	/**
	 * mappes a BPMN timer event to a YAWL timer task
	 * @param model containing model
	 * @param decomposition containing decomposition
	 * @param node the Intermediate Timer Event to be mapped
	 * @param nodeMap the hashmap for BPMN and YAWL nodes
	 * @param attached whether the node is attached to a activity (for error handling)
	 * @return the timer task
	 */
	private YNode mapTimerEvent(YModel model, YDecomposition decomposition, Node node,
			HashMap<Node, YNode> nodeMap, Boolean attached) {
		YTask timerTask = decomposition.createTask(generateId("timer"), "TimerTask");
		IntermediateTimerEvent timerEvent = (IntermediateTimerEvent)node;
		Date timeDate = null;
		
		try {
			if(!timerEvent.getTimeDate().isEmpty()){
				DateFormat dateFormatter = new SimpleDateFormat("dd/MM/yy");
				timeDate = dateFormatter.parse(timerEvent.getTimeDate());
			}
		} catch (ParseException e) {
			e.printStackTrace();
		}
		YTimer timer = new YTimer(YTimer.Trigger.OnEnabled, timerEvent.getTimeCycle(), timeDate);
		timerTask.setTimer(timer);
		if(attached)
			timer.setTrigger(YTimer.Trigger.OnExecuting);
		
		YVariable timerVariable = new YVariable();
		timerVariable.setName(timerTask.getID() + "_timer");
		timerVariable.setType("string");
		timerVariable.setReadOnly(false);
		decomposition.getLocalVariables().add(timerVariable);
		
		String timerQuery = "&lt;" + timerVariable.getName() + "&gt;{/" + decomposition.getID() + "/" + timerVariable.getName() + "/text()}&lt;/" + timerVariable.getName() + "&gt;";			
		YVariableMapping timerStartVarMap = new YVariableMapping(timerQuery, timerVariable);			
		timerTask.getStartingMappings().add(timerStartVarMap);
		
		YDecomposition taskDecomposition = null;
		taskDecomposition = setTaskDecomposition(model, taskDecomposition, timerTask);
		taskDecomposition.getInputParams().add(timerVariable);
		
		return timerTask;
	}

	/**
	 * @param exitTask
	 * @param nodeMap
	 * @param dec 
	 * @param terminateEvents 
	 */
	private void linkYawlElements(HashMap<Node, YNode> nodeMap, YDecomposition dec, LinkedList<EndTerminateEvent> terminateEvents) {		
		Map<YNode, Integer> counter = new HashMap<YNode, Integer>();
		
		for (Node node : nodeMap.keySet()) {
			YEdge defaultEdge = null;
			YNode defaultSourceTask = null;
			YNode sourceTask;
			
			if ((node instanceof EndErrorEvent) || (node instanceof EndTerminateEvent)){
				sourceTask = nodeMap.get(node);				
				dec.createEdge(sourceTask, dec.getOutputCondition(), false, "", 1);
				if (node instanceof EndTerminateEvent)
					terminateEvents.add((EndTerminateEvent) node);
				continue;
			}
			for (SequenceFlow edge : node.getOutgoingSequenceFlows()) {
				String predicate = "";
				
				Node target = (Node) edge.getTarget();
				YNode targetTask = nodeMap.get(target);
				sourceTask = nodeMap.get(node);
				
				if (sourceTask == null || targetTask == null)
					continue;
				
				if (!sourceTask.equals(targetTask) || (node instanceof Gateway && node == target)) {
					if (!counter.containsKey(sourceTask))
						counter.put(sourceTask, 0);
						
					Integer order = counter.get(sourceTask) + 1;
					counter.put(sourceTask, order);
					
					if(edge.getConditionType() == ConditionType.EXPRESSION){
						predicate = edge.getConditionExpression();
					} else if(edge.getConditionType() == ConditionType.DEFAULT){
						predicate = "";
						
						defaultSourceTask = sourceTask;
						order--;
						counter.put(sourceTask, order);
						defaultEdge = new YEdge(sourceTask, targetTask, true, predicate, 0);
						continue;
					}
					
					dec.createEdge(sourceTask, targetTask, false, predicate, order);
				}
			}
			if(defaultEdge != null){
				Integer order = counter.get(defaultSourceTask) + 1;
				counter.put(defaultSourceTask, order);
				
				defaultEdge.setOrdering(order);
				
				dec.addEdge(defaultEdge);
			}
		}
	}

	private void mapEndTerminateToCancellationSet(YNode terminateNode, HashMap<Node, YNode> nodeMap) {
		
		YTask terminateTask = (YTask)terminateNode;
		ArrayList<YNode> cancellationSet = new ArrayList<YNode>();
		
		for(YNode ynode : nodeMap.values()){
			if((ynode instanceof YInputCondition) || (ynode instanceof YOutputCondition)){
				continue;
			}
			if(ynode.equals(terminateNode))
				continue;
			
			cancellationSet.add(ynode);
		}
		
		terminateTask.getCancellationSet().addAll(cancellationSet);
	}

	/**
	 * @param model
	 * @param node
	 * @param nodeMap
	 * @return
	 */
	private YNode mapConditionFromEventBased(YModel model, YDecomposition dec, Node node,
			HashMap<Node, YNode> nodeMap) {
		
		YCondition cond = null;
	
		YNode preYNode = nodeMap.get((Node)node.getIncomingSequenceFlows().get(0).getSource());
		if(preYNode instanceof YCondition)
			cond = (YCondition)preYNode;
		else
			cond = dec.createCondition(generateId("EXorGW"), "Condition");
		
		return cond;
	}
	
	/**
	 * @param model
	 * @param act
	 * @param actMap
	 * @return
	 */
	private YNode mapIntermediateEvent(YModel model, YDecomposition dec, Node node,
			HashMap<Node, YNode> nodeMap) {
		
		YTask task = dec.createTask(generateId("intermediate"), "TaskMappedFromIntermediateEvent");
		
		YDecomposition decomposition = null;
		decomposition = setTaskDecomposition(model, decomposition, task);
		
		return task;
	}
	
	/**
	 * @param model
	 * @param act
	 * @param actMap
	 * @return
	 */
	private YNode mapIntermediateMessageEvent(YModel model, YDecomposition dec, Node node,
			HashMap<Node, YNode> nodeMap) {
		
		YTask task = dec.createTask(generateId("msg"), "TaskMappedFromIntermediateMessageEvent");
		
		YDecomposition decomposition = null;
		decomposition = setTaskDecomposition(model, decomposition, task);
		
		return task;
	}

	/**
	 * @param model
	 * @param act
	 * @param actMap
	 * @return
	 */
	private YNode mapTask(YModel model, YDecomposition dec, Activity activity,
			HashMap<Node, YNode> nodeMap) {
		YDecomposition decomposition = null;
		YNode task = mapTask(model, dec, activity, decomposition);		
		return task;
	}

	/**
	 * @param model
	 * @param node
	 * @param nodeMap
	 * @return
	 */
	private YNode mapCompositeTask(BPMNDiagram diagram, YModel model, YDecomposition decomposition, Activity activity, HashMap<Node, YNode> nodeMap) {
	
		YDecomposition subdec = mapDecomposition(diagram, model, (SubProcess)activity);
		YTask task = (YTask)mapTask(model, decomposition, activity, subdec);
		
		return task;
	}

	/**
	 * @param node
	 * @param isComposite
	 * @return
	 */
	private YNode mapTask(YModel model, YDecomposition dec, Activity activity, YDecomposition subDec) {
		ArrayList<YVariable> taskVariablesLocal = new ArrayList<YVariable>();
		ArrayList<YVariable> taskVariablesInput = new ArrayList<YVariable>();
		ArrayList<YVariable> taskVariablesOutput = new ArrayList<YVariable>();
		Boolean isACompositeTask = false;
		
		YTask task = dec.createTask(generateId("task"), activity.getLabel());
		if(subDec != null){
			isACompositeTask = true;
		}
		
		mapActivityProperties(dec, activity, task, taskVariablesLocal);
		
		mapAllActivityAssignments(dec, activity, taskVariablesLocal,
				taskVariablesInput, taskVariablesOutput, task);
		
		if(!isACompositeTask){
			
			copyParametersToDecomposition(dec, taskVariablesLocal,
					taskVariablesInput, taskVariablesOutput);
			
			//add a new decomposition for the task to the model
			subDec = setTaskDecomposition(model, subDec, task);
			
			assignParametersToDecomposition(taskVariablesLocal,
					taskVariablesInput, taskVariablesOutput, subDec);
			
			isACompositeTask = true;
		}
		
		if (isACompositeTask)
			task.setDecomposesTo(subDec);
		
		//Multiple Instances
		if (activity.isMultipleInstance()) {
			mapMultipleInstanceInfo(dec, activity, task);
			
			// Decomposition
			subDec = setTaskDecomposition(model, subDec, task);
		}
		
		if (activity.getLoopType() == Activity.LoopType.Standard) {
			if (!loopingActivities.containsKey(dec))
				loopingActivities.put(dec, new LinkedList<Node>());
			loopingActivities.get(dec).add(activity);
		}
		
		if(isLoopingActivityBySequenceFlow(activity)){
			if (!loopingActivities.containsKey(dec))
				loopingActivities.put(dec, new LinkedList<Node>());
			loopingActivities.get(dec).add(activity);
		}
		mapTaskResourcingInfo(activity, task);
		
		return task;
	}

	/**
	 * @param dec
	 * @param activity
	 * @param task
	 */
	private void mapMultipleInstanceInfo(YDecomposition dec, Activity activity,
			YTask task) {
		task.setIsMultipleTask(true);
		task.setXsiType("MultipleInstanceExternalTaskFactsType");
			
		YMultiInstanceParam miParam = mapMultiInstanceParameters(activity);

		task.setMiParam(miParam);
			
		YVariable local = defineInputVariable(task);
		dec.getInputParams().add(local);
			
		YVariable inputParam = defineInputVariable(task);
		task.getDecomposesTo().getInputParams().add(inputParam);
		
		miParam.setMiDataInput(mapMiDataInput(dec, local));
		
		miParam.setMiDataOutput(mapMiDataOutput(dec, local));
	}

	/**
	 * @param dec
	 * @param local
	 * @return
	 */
	private YMIDataOutput mapMiDataOutput(YDecomposition dec, YVariable local) {
		YMIDataOutput miDataOutput = new YMIDataOutput();
		miDataOutput.setFormalOutputExpression("/" + dec.getID() + "/" + local.getName());
		miDataOutput.setOutputJoiningExpression(" ");
		miDataOutput.setResultAppliedToLocalVariable(local);
		return miDataOutput;
	}

	/**
	 * @param dec
	 * @param local
	 * @return
	 */
	private YMIDataInput mapMiDataInput(YDecomposition dec, YVariable local) {
		YMIDataInput miDataInput = new YMIDataInput();
		miDataInput.setExpression("/" + dec.getID() + "/" + local.getName());
		miDataInput.setSplittingExpression(" ");
		miDataInput.setFormalInputParam(local);
		return miDataInput;
	}

	/**
	 * @param task
	 * @return
	 */
	private YVariable defineInputVariable(YTask task) {
		YVariable local = new YVariable();
		local.setName(task.getID() + "_input");
		local.setType("string");
		return local;
	}

	/**
	 * @param model
	 * @param subDec
	 * @param task
	 * @return
	 */
	private YDecomposition setTaskDecomposition(YModel model,
			YDecomposition subDec, YTask task) {
		if(subDec == null){
			subDec = model.createDecomposition(task.getID());
			subDec.setXSIType(XsiType.WebServiceGatewayFactsType);
			task.setDecomposesTo(subDec);
		}
		return subDec;
	}

	/**
	 * @param activity
	 * @return
	 */
	private YMultiInstanceParam mapMultiInstanceParameters(Activity activity) {
		YMultiInstanceParam param = new YMultiInstanceParam();
		param.setMinimum(1);
		param.setMaximum(2147483647);

		mapMultipleInstanceThreshold(activity, param);

		param.setCreationMode(CreationMode.STATIC);
		return param;
	}

	/**
	 * @param activity
	 * @param param
	 */
	private void mapMultipleInstanceThreshold(Activity activity,
			YMultiInstanceParam param) {
		// the number 2147483647 stands for infinite in YAWL
		if(activity.getMiFlowCondition() == Activity.MIFlowCondition.One){
			param.setThreshold(1);
		} else if (activity.getMiFlowCondition() == Activity.MIFlowCondition.All){
			param.setThreshold(2147483647);
		} else if (activity.getMiFlowCondition() == Activity.MIFlowCondition.Complex){
			param.setThreshold(2147483647);
		}
	}

	/**
	 * @param decomposition
	 * @param taskVariablesLocal
	 * @param taskVariablesInput
	 * @param taskVariablesOutput
	 */
	private void copyParametersToDecomposition(YDecomposition decomposition,
			ArrayList<YVariable> taskVariablesLocal,
			ArrayList<YVariable> taskVariablesInput,
			ArrayList<YVariable> taskVariablesOutput) {
		decomposition.getLocalVariables().addAll(taskVariablesLocal);
		decomposition.getInputParams().addAll(taskVariablesInput);
		decomposition.getOutputParams().addAll(taskVariablesOutput);
	}

	/**
	 * @param activity
	 * @param task
	 */
	private void mapTaskResourcingInfo(Activity activity, YTask task) {
		if(activity instanceof Task){
			Task bpmnTask = (Task)activity;
			YResourcing resourcingParam = new YResourcing();

			mapOfferInfo(bpmnTask, resourcingParam);
			
			mapAllocateInfo(bpmnTask, resourcingParam);

			mapStartInfo(bpmnTask, resourcingParam);
			
			task.setResourcing(resourcingParam);
			
			if (bpmnTask.getParent() instanceof Lane){
				ResourcingType resource = resourcingNodeMap.get(bpmnTask.getParent());
				DistributionSet distributionSet = new DistributionSet();
				distributionSet.getInitialSetList().add(resource);
				
				if(resourcingParam.getOffer().equals(InitiatorType.SYSTEM))
					resourcingParam.setOfferDistributionSet(distributionSet);
				
				if(resourcingParam.getAllocate().equals(InitiatorType.SYSTEM))
					resourcingParam.setAllocateDistributionSet(distributionSet);
			}
		}
	}

	/**
	 * @param bpmnTask
	 * @param resourcingParam
	 */
	private void mapStartInfo(Task bpmnTask, YResourcing resourcingParam) {
		if((bpmnTask.getYawl_startedBy() != null) && bpmnTask.getYawl_startedBy().toLowerCase().equals("system"))
			resourcingParam.setStart(InitiatorType.SYSTEM);
		else
			// by default set it to user
			resourcingParam.setStart(InitiatorType.USER);
	}

	/**
	 * @param bpmnTask
	 * @param resourcingParam
	 */
	private void mapAllocateInfo(Task bpmnTask, YResourcing resourcingParam) {
		if((bpmnTask.getYawl_allocatedBy() != null) && bpmnTask.getYawl_allocatedBy().toLowerCase().equals("system"))
			resourcingParam.setAllocate(InitiatorType.SYSTEM);
		else
			// by default set it to user
			resourcingParam.setAllocate(InitiatorType.USER);
	}

	/**
	 * @param bpmnTask
	 * @param resourcingParam
	 */
	private void mapOfferInfo(Task bpmnTask, YResourcing resourcingParam) {
		if((bpmnTask.getYawl_offeredBy() != null) && bpmnTask.getYawl_offeredBy().toLowerCase().equals("system"))
			resourcingParam.setOffer(InitiatorType.SYSTEM);
		else
			// by default set it to user
			resourcingParam.setOffer(InitiatorType.USER);
	}

	/**
	 * @param taskVariablesLocal
	 * @param taskVariablesInput
	 * @param taskVariablesOutput
	 * @param taskDecomposition
	 */
	private void assignParametersToDecomposition(
			ArrayList<YVariable> taskVariablesLocal,
			ArrayList<YVariable> taskVariablesInput,
			ArrayList<YVariable> taskVariablesOutput,
			YDecomposition taskDecomposition) {
		for(YVariable mappedVariable: taskVariablesLocal){
			taskDecomposition.getInputParams().add(mappedVariable);
			taskDecomposition.getOutputParams().add(mappedVariable);
		}
		
		for(YVariable mappedVariable: taskVariablesInput){
			taskDecomposition.getInputParams().add(mappedVariable);
		}
		
		for(YVariable mappedVariable: taskVariablesOutput){
			taskDecomposition.getOutputParams().add(mappedVariable);
		}
	}

	/**
	 * @param dec
	 * @param activity
	 * @param taskVariablesLocal
	 * @param taskVariablesInput
	 * @param taskVariablesOutput
	 * @param task
	 */
	private void mapAllActivityAssignments(YDecomposition dec,
			Activity activity, ArrayList<YVariable> taskVariablesLocal,
			ArrayList<YVariable> taskVariablesInput,
			ArrayList<YVariable> taskVariablesOutput, YTask task) {
		if(activity.getAssignments().size() > 0){

			for(Assignment assignment : activity.getAssignments()){
				 
				if(assignment.getAssignTime() == Assignment.AssignTime.Start){
					mapActivityAssignments(dec, taskVariablesLocal,
							taskVariablesInput, task, assignment,
							task.getStartingMappings(), dec.getID());
				}
				
				if(assignment.getAssignTime() == Assignment.AssignTime.End){
					mapActivityAssignments(dec, taskVariablesLocal,
							taskVariablesOutput, task, assignment,
							task.getCompletedMappings(), task.getID());
				}
			}

		}
	}

	/**
	 * @param dec
	 * @param taskVariablesLocal
	 * @param taskVariables
	 * @param task
	 * @param assignment
	 */
	private void mapActivityAssignments(YDecomposition dec,
			ArrayList<YVariable> taskVariablesLocal,
			ArrayList<YVariable> taskVariables, YTask task,
			Assignment assignment,
			ArrayList<YVariableMapping> taskMapping,
			String querySourceId) {
		Boolean propertyIsMapped = false;
		YVariable mappedVariable = null;
		
		//the mappings have to be accessed, because the task can still have no decomposition
		taskVariables.addAll(taskVariablesLocal);
		for (YVariable variable : taskVariables) {
			if(variable.getName().equalsIgnoreCase(assignment.getTo())){
				propertyIsMapped = true;
				mappedVariable = variable;
				break;
			}
		}
		
		if(!propertyIsMapped){
			//add a local variable in the given decomposition
			mappedVariable = new YVariable();
			
			mappedVariable.setName(assignment.getTo());
			mappedVariable.setType("string");
			
			taskVariablesLocal.add(mappedVariable);
		}
		
		//set the variable mappings for the task
		String query = "&lt;" + mappedVariable.getName() + "&gt;{/" + querySourceId + "/" + mappedVariable.getName() + "/text()}&lt;/" + mappedVariable.getName() +"&gt;";
		Boolean sameMappingExists = false;
		for(YVariableMapping mapping : taskMapping){
			if(mapping.getQuery().equalsIgnoreCase(query)){
				sameMappingExists = true;
				break;
			}
		}
		if(!sameMappingExists){
			YVariableMapping localVarMap = new YVariableMapping(query, mappedVariable);			
			taskMapping.add(localVarMap);
		}
	}

	/**
	 * @param dec
	 * @param activity
	 * @param task
	 * @param taskVariablesLocal
	 */
	private void mapActivityProperties(YDecomposition dec, Activity activity,
			YTask task, ArrayList<YVariable> taskVariablesLocal) {
		if(activity.getProperties().size() > 0){
			
			for(Property property : activity.getProperties()){
				//add a local variable in the given decomposition
				YVariable mappedVariable = new YVariable();
				
				mappedVariable.setName(property.getName());
				if(!property.getType().equalsIgnoreCase("null"))
					mappedVariable.setType(property.getType().toLowerCase());
				else
					//set string as the default type if no type specified
					mappedVariable.setType("string");
				mappedVariable.setInitialValue(property.getValue());
				
				taskVariablesLocal.add(mappedVariable);
				
				//set the variable mappings for the task
				String startQuery = "&lt;" + mappedVariable.getName() + "&gt;{/" + dec.getID() + "/" + mappedVariable.getName() + "/text()}&lt;/" + mappedVariable.getName() +"&gt;";			
				YVariableMapping localStartVarMap = new YVariableMapping(startQuery, mappedVariable);			
				task.getStartingMappings().add(localStartVarMap);
				
				String completeQuery = "&lt;" + mappedVariable.getName() + "&gt;{/" + task.getID() + "/" + mappedVariable.getName() + "/text()}&lt;/" + mappedVariable.getName() +"&gt;";
				YVariableMapping localCompleteVarMap = new YVariableMapping(completeQuery, mappedVariable);			
				task.getCompletedMappings().add(localCompleteVarMap);
			}			
		}
	}	
}