/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package lockstep;

/**
 *
 * @author Raff
 */
abstract public class LockstepCoreThread extends Thread
{
    abstract public void temporaryName(int nodeID);
    abstract void secondTemporaryName(int nodeID);
}
