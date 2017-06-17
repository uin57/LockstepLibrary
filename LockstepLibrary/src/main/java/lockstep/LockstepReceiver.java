/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package lockstep;

import lockstep.messages.simulation.InputMessageArray;
import lockstep.messages.simulation.InputMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import lockstep.messages.simulation.DisconnectionSignal;
import lockstep.messages.simulation.FrameACK;
import lockstep.messages.simulation.KeepAlive;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class LockstepReceiver extends Thread
{
    public static final int RECEIVER_FROM_SERVER_ID = 0;
    
    volatile DatagramSocket dgramSocket;
    volatile Map<Integer, ReceivingQueue> receivingQueues;
    volatile Map<Integer, TransmissionQueue> transmissionFrameQueues;
    volatile ACKQueue ackQueue;
    static final int MAX_PAYLOAD_LENGTH = 300;
    
    private static final Logger LOG = LogManager.getLogger(LockstepReceiver.class);
    
    private final LockstepCoreThread coreThread;
    
    private final String name;
    
    int receiverID;
        
    public LockstepReceiver(DatagramSocket socket, LockstepCoreThread coreThread , Map<Integer, ReceivingQueue> receivingQueues, Map<Integer, TransmissionQueue> transmissionFrameQueues, String name, int ownID, ACKQueue ackQueue)
    {
        dgramSocket = socket;
        this.coreThread = coreThread;
        this.receivingQueues = receivingQueues;
        this.transmissionFrameQueues = transmissionFrameQueues;
        this.name = name;
        this.receiverID = ownID;
        this.ackQueue = ackQueue;
    }
    
    @Override
    public void run()
    {
        Thread.currentThread().setName(name);
        
        while(true)
        {            
            try
            {
                if(Thread.interrupted())
                    throw new InterruptedException();
                
                DatagramPacket p = new DatagramPacket(new byte[MAX_PAYLOAD_LENGTH], MAX_PAYLOAD_LENGTH);
                this.dgramSocket.receive(p);
                try(
                    ByteArrayInputStream bain = new ByteArrayInputStream(p.getData());
                    GZIPInputStream gzin = new GZIPInputStream(bain);
                    ObjectInputStream oin = new ObjectInputStream(gzin);
                )
                {
                    Object obj = oin.readObject();
                    messageSwitch(obj);
                }
            }
            catch(IOException  disconnectionException)
            {
                LOG.info("Receiver entering termination phase: disconnection detected");
                dgramSocket.close();
                signalDisconnection();
                handleDisconnection(receiverID);
                LOG.info("Receiver terminated");
                return;
            }
            catch(ClassNotFoundException invalidMessageEx)
            {
                LOG.info("Receiver entering termination phase: invalid message received");
                signalDisconnection();
                handleDisconnection(receiverID);
                LOG.info("Receiver terminated");
                return;
            }
            catch(InterruptedException intEx)
            {
                LOG.info("Receiver disconnected: interruption received");
                return;
            }
        }
    }
    
    private void messageSwitch(Object obj) throws ClassNotFoundException
    {
        if(obj instanceof InputMessage)
        {
            InputMessage input = (InputMessage)obj;
            this.processInput(input);
        }
        else if(obj instanceof InputMessageArray)
        {
            InputMessageArray inputs = (InputMessageArray)obj;
            this.processInput(inputs);
        }
        else if(obj instanceof FrameACK)
        {
            FrameACK ack = (FrameACK)obj;
            this.processACK(ack);
        }
        else if(obj instanceof KeepAlive)
        {   
            //Socket connection timeout is reset at packet reception
        }
        else 
        {
            throw new ClassNotFoundException("Unrecognized message received");
        }
    }
    
    private void processInput(InputMessage input)
    {
        LOG.debug("1 InputMessage received from " + input.senderID + ": " + input.frame.getFrameNumber());
        ReceivingQueue receivingQueue = this.receivingQueues.get(input.senderID);
        FrameACK frameACK = receivingQueue.push(input.frame);
        frameACK.setSenderID(input.senderID);
        ackQueue.pushACKs(frameACK);

        if(input.frame.getCommand() instanceof DisconnectionSignal)
            handleDisconnection(input.senderID);
    }

    private void processInput(InputMessageArray inputs)
    {
        String numbers = "";
        for(FrameInput frame : inputs.frames)
            numbers += frame.getFrameNumber() + ", ";
        LOG.debug("" + inputs.frames.length + " InputMessages received from " + inputs.senderID + ": [ " + numbers + "]");
        ReceivingQueue receivingQueue = this.receivingQueues.get(inputs.senderID);
        FrameACK frameACK = receivingQueue.push(inputs.frames);
        frameACK.setSenderID(inputs.senderID);
        ackQueue.pushACKs(frameACK);
        
        if(inputs.frames[inputs.frames.length - 1].getCommand() instanceof DisconnectionSignal)
            handleDisconnection(inputs.senderID);
    }
    
    private void processACK(FrameACK ack)
    {
        TransmissionQueue transmissionFrameQueue = this.transmissionFrameQueues.get(ack.senderID);
        transmissionFrameQueue.processACK(ack);
    }
    
    private void handleDisconnection(int disconnectedNode)
    {
        coreThread.disconnectTransmittingQueues(disconnectedNode);
    }

    private void signalDisconnection()
    {
        if(receiverID == RECEIVER_FROM_SERVER_ID)
        {
            for(ReceivingQueue receveingQueue : receivingQueues.values())
            {
                receveingQueue.push(new FrameInput(receveingQueue.getACK().cumulativeACK + 1, new DisconnectionSignal()));
            }
        }
        else   
        {
            ReceivingQueue receveingQueue = receivingQueues.get(receiverID);
            if(receveingQueue != null)
                receveingQueue.push(new FrameInput(receveingQueue.getACK().cumulativeACK + 1, new DisconnectionSignal()));
        }
    }
}
