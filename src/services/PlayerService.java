/*******************************************************************************
 * Copyright (c) 2013 <Project SWG>
 * 
 * This File is part of NGECore2.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Using NGEngine to work with NGECore2 is making a combined work based on NGEngine. 
 * Therefore all terms and conditions of the GNU Lesser General Public License cover the combination.
 ******************************************************************************/
package services;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;

import protocol.swg.ClientIdMsg;
import protocol.swg.ExpertiseRequestMessage;
import protocol.swg.ServerTimeMessage;
import resources.common.FileUtilities;
import resources.common.Opcodes;
import resources.common.SpawnPoint;
import resources.objects.Buff;
import resources.objects.building.BuildingObject;
import resources.objects.cell.CellObject;
import resources.objects.creature.CreatureObject;
import resources.objects.player.PlayerObject;
import services.sui.SUIService.ListBoxType;
import services.sui.SUIWindow;
import services.sui.SUIWindow.Trigger;
import services.sui.SUIWindow.SUICallback;

import main.NGECore;

import engine.clients.Client;
import engine.resources.objects.SWGObject;
import engine.resources.scene.Point3D;
import engine.resources.service.INetworkDispatch;
import engine.resources.service.INetworkRemoteEvent;

@SuppressWarnings("unused")

public class PlayerService implements INetworkDispatch {
	
	private NGECore core;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

	public PlayerService(final NGECore core) {
		this.core = core;
		scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				ServerTimeMessage time = new ServerTimeMessage(System.currentTimeMillis() / 1000);
				IoBuffer packet = time.serialize();
				synchronized(core.getActiveConnectionsMap()) {
					for(Client c : core.getActiveConnectionsMap().values()) {
						if(c.getParent() != null) {
							c.getSession().write(packet);
						}
					}
				}
			}
			
		}, 0, 30, TimeUnit.SECONDS);
	}
	
	public void postZoneIn(final CreatureObject creature) {
		
		scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				
				PlayerObject player = (PlayerObject) creature.getSlottedObject("ghost");
				player.setTotalPlayTime(player.getTotalPlayTime() + 30);
				player.setLastPlayTimeUpdate(System.currentTimeMillis());
				
			}
			
		}, 30, 30, TimeUnit.SECONDS);
		
		scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				
				if(creature.getAction() < creature.getMaxAction() && creature.getPosture() != 14)
					creature.setAction(creature.getAction() + 200);
				
			}
			
		}, 0, 1000, TimeUnit.MILLISECONDS);

		scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				
				if(creature.getHealth() < creature.getMaxHealth() && creature.getCombatFlag() == 0 && creature.getPosture() != 13 && creature.getPosture() != 14)
					creature.setHealth(creature.getHealth() + 300);
				
			}
			
		}, 0, 1100, TimeUnit.MILLISECONDS);
		
		/*scheduler.scheduleAtFixedRate(new Runnable() {

			@Override
			public void run() {
				
				if(creature.getCombatFlag() == 1)
					creature.resetHAMList();
				
			}
			
		}, 0, 10, TimeUnit.SECONDS);*/


	}

	@Override
	public void insertOpcodes(Map<Integer, INetworkRemoteEvent> swgOpcodes, Map<Integer, INetworkRemoteEvent> objControllerOpcodes) {
		
		swgOpcodes.put(Opcodes.CmdSceneReady, new INetworkRemoteEvent() {

			@Override
			public void handlePacket(IoSession session, IoBuffer buffer) throws Exception {
				
				
			}
			
		});
		
		swgOpcodes.put(Opcodes.ExpertiseRequestMessage, new INetworkRemoteEvent() {

			@Override
			public void handlePacket(IoSession session, IoBuffer buffer) throws Exception {
				
				buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);
				buffer.position(0);
				
				ExpertiseRequestMessage expertise = new ExpertiseRequestMessage();
				expertise.deserialize(buffer);

				Client client = core.getClient((Integer) session.getAttribute("connectionId"));
				if(client == null) {
					System.out.println("NULL Client");
					return;
				}

				if(client.getParent() == null)
					return;
				
				CreatureObject creature = (CreatureObject) client.getParent();
				
				for(String expertiseName : expertise.getExpertiseSkills()) {
					handleExpertiseSkillBox(creature, expertiseName);
				}
				
				
			}
			
		});

		
	}
	
	public void handleExpertiseSkillBox(CreatureObject creature, String expertiseBox) {
		
		if(!FileUtilities.doesFileExist("scripts/expertise/" + expertiseBox + ".py"))
			return;
		
		core.scriptService.callScript("scripts/expertise/", "addExpertisePoint", expertiseBox, core, creature);
		
	}
	
	public void sendCloningWindow(CreatureObject creature, final boolean pvpDeath) {
		
		//if(creature.getPosture() != 14)
		//	return;
		
		List<SWGObject> cloners = core.staticService.getCloningFacilitiesByPlanet(creature.getPlanet());
		Map<Long, String> cloneData = new HashMap<Long, String>();
		Point3D position = creature.getWorldPosition();
		
		SWGObject preDesignatedCloner = null;
		
		if(creature.getAttachment("preDesignatedCloner") != null) {
			preDesignatedCloner = core.objectService.getObject((long) creature.getAttachment("preDesignatedCloner"));
			if(preDesignatedCloner != null) 
				cloneData.put(preDesignatedCloner.getObjectID(), core.mapService.getClosestCityName(preDesignatedCloner) /*+ " (" + String.valueOf(position.getDistance2D(cloner.getPosition())) + "m)"*/);
		}
		
		for(SWGObject cloner : cloners) {
			
			if(cloner != preDesignatedCloner)
				cloneData.put(cloner.getObjectID(), core.mapService.getClosestCityName(cloner) /*+ " (" + String.valueOf(position.getDistance2D(cloner.getPosition())) + "m)"*/);
			
		}
		
		final SUIWindow window = core.suiService.createListBox(ListBoxType.LIST_BOX_OK_CANCEL, "@base_player:revive_title", "Select the desired option and click OK.", 
				cloneData, creature, null, 0);
		Vector<String> returnList = new Vector<String>();
		returnList.add("List.lstList:SelectedRow");
		
		window.addHandler(0, "", Trigger.TRIGGER_OK, returnList, new SUICallback() {

			@SuppressWarnings("unchecked")
			@Override
			public void process(SWGObject owner, int eventType, Vector<String> returnList) {
				
			//	if(((CreatureObject)owner).getPosture() != 14)
			//		return;
								
				int index = Integer.parseInt(returnList.get(0));
				
				if(window.getObjectIdByIndex(index) == 0 || core.objectService.getObject(window.getObjectIdByIndex(index)) == null)
					return;
					
					
				SWGObject cloner = core.objectService.getObject(window.getObjectIdByIndex(index));
					
				if(cloner.getAttachment("spawnPoints") == null)
					return;
				
				Vector<SpawnPoint> spawnPoints = (Vector<SpawnPoint>) cloner.getAttachment("spawnPoints");
				
				SpawnPoint spawnPoint = spawnPoints.get(new Random().nextInt(spawnPoints.size()));

				handleCloneRequest((CreatureObject) owner, (BuildingObject) cloner, spawnPoint, pvpDeath);
				
			}
			
		});
		
		core.suiService.openSUIWindow(window);
		
	}
	
	public void handleCloneRequest(CreatureObject creature, BuildingObject cloner, SpawnPoint spawnPoint, boolean pvpDeath) {
		
		CellObject cell = cloner.getCellByCellNumber(spawnPoint.getCellNumber());
		
		if(cell == null)
			return;
		
		core.simulationService.transferToPlanet(creature, cloner.getPlanet(), spawnPoint.getPosition(), spawnPoint.getOrientation(), cell);
		
		creature.setHealth(creature.getMaxHealth());
		creature.setAction(creature.getMaxAction());
		
		creature.setPosture((byte) 0);
		creature.setSpeedMultiplierBase(1);
		creature.setTurnRadius(1);
		
		if(pvpDeath) {
			List<Buff> buffs = new ArrayList<Buff>(creature.getBuffList().get());
			
			for(Buff buff : buffs) {
				if(buff.isDecayOnPvPDeath())
					buff.incDecayCounter();
			}
			
			creature.updateAllBuffs();
		}
		
		creature.setFactionStatus(0);
		core.buffService.addBuffToCreature(creature, "cloning_sickness");
		
	}

	@Override
	public void shutdown() {
		// TODO Auto-generated method stub
		
	}
	
}
