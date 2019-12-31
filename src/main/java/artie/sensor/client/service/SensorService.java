package artie.sensor.client.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import artie.sensor.client.enums.SensorActionEnum;
import artie.sensor.client.event.GenericArtieEvent;
import artie.sensor.client.model.Sensor;
import artie.sensor.client.repository.SensorRepository;
import artie.sensor.common.enums.ConfigurationEnum;

@Service
public class SensorService {
	
	@Autowired
	private SensorRepository sensorRepository;
	
	@Autowired
	private ApplicationEventPublisher applicationEventPublisher;
	
	@Value("${artie.client.minsensorport}")
	private Long minSensorPort;
	
	@Value("${artie.client.waitsensorstart}")
	private Long waitSensorStart;
	
	@Value("${artie.client.datasource.url}")
	private String sensorDataSourceUrl;
	
	@Value("${spring.datasource.driverClassName}")
	private String dataSourceDriver;
	
	@Value("${spring.datasource.username}")
	private String dataSourceUser;
	
	@Value("${spring.datasource.password}")
	private String dataSourcePasswd;
	
	private Logger logger = LoggerFactory.getLogger(this.getClass());
	private List<Sensor> sensorList = new ArrayList<Sensor>();
	private boolean loadingProcessFinished = false;
	private RestTemplate restTemplate = new RestTemplate();
	
	
	/**
	 * Function to destroy all the living sensor processes
	 */
	@PreDestroy
	public void destroy(){
		
		//Stops all the sensors
		this.stopSensors();	
	}
	
	/**
	 * Function to shutdown all the sensors
	 */
	public void stopSensors(){
		
		//Stopping all the sensors
		for(Sensor sensor : sensorList){
			
			//1- Stopping the service
			this.restTemplate.getForEntity("http://localhost:" + sensor.getSensorPort() + "/artie/sensor/" + sensor.getSensorName() + "/stop", String.class);
			
			//2- Stopping the springboot
			this.restTemplate.postForEntity("http://localhost:" + sensor.getManagementPort() + "/actuator/shutdown", "", String.class);
			
			//Triggering the action
			this.applicationEventPublisher.publishEvent(new GenericArtieEvent(this, SensorActionEnum.STOP.toString(), sensor.getSensorName(), true));
			
			//Logging the action
			this.logger.debug("Sensor - " + SensorActionEnum.STOP.toString() + " - " + sensor.getSensorName() + " - OK");
		}
	}
	
	/**
	 * Function to add the sensor data
	 */
	@Scheduled(fixedRateString="${artie.client.getdata.rate}")
	public void getSensorData(){
		
		//If the loading process has been finished, we get all the data from the sensors
		if(this.loadingProcessFinished){
			for(Sensor sensor : sensorList){
				
				//Gets the sensor object
				this.restTemplate.getForEntity("http://localhost:" + sensor.getSensorPort() + "/artie/sensor/" + sensor.getSensorName() + "/sendSensorData", String.class);

				//Logging the results
				this.logger.debug("Sensor - " + SensorActionEnum.SEND.toString() + " - " + sensor.getSensorName() + " - OK");
				System.out.println("Sensor - " + SensorActionEnum.SEND.toString() + " - " + sensor.getSensorName() + " - OK");
			}
		}
	}	
	
	/**
	 * Function to get the next free port in the client system
	 * @return
	 */
	private Long getNextPort(){
		
		Long nextPort = minSensorPort;
		
		//1- Getting all the sensors ordered by sensor number
		Optional<Sensor> sensorList = sensorRepository.findByOrderBySensorPortDesc();
		
		//2- If there is a sensor added in the system, we set the new sensor port as the lastest one + 10
		if(sensorList.isPresent()){
			nextPort = sensorList.get().getSensorPort();
			nextPort += 10;
		}
		
		return nextPort;
	}
	
	/**
	 * Add a new sensor to the client
	 * @param pathToJar
	 */
	public void add(String pathToJar){
		
		//1- Getting the sensor name from the jar file name
		String[] pathElements = pathToJar.split("/");
		String pathElement = pathElements[pathElements.length-1];
		pathElements = pathElement.split("-");
		String sensorName=pathElements[0];
		
		//2- Getting the sensor port and the management port
		Long sensorPort = this.getNextPort();
		Long managementPort = sensorPort + 1;
		
		//3- Inserting the sensor in the system
		this.sensorRepository.save(new Sensor((long) 0, pathToJar, sensorPort, managementPort, sensorName));
		
		//4- Triggering the event
		this.applicationEventPublisher.publishEvent(new GenericArtieEvent(this, SensorActionEnum.ADD.toString(), sensorName, true));

		//5- Logging the action
		this.logger.debug("Sensor - " + SensorActionEnum.ADD.toString() + " - " + sensorName + " - OK");
	}
	
	/**
	 * Function to run each sensor added in database
	 */
	public void run(){

		//1- Gets all the sensors from the database
		List<Sensor> sensorList = (List<Sensor>) sensorRepository.findAll();
		
		//2- Prepares the configuration
		Map<String,String> sensorConfiguration = new HashMap<>();
		ObjectMapper mapper = new ObjectMapper();
		
		//2- Running all the sensors with the parameters stored in database
		for(Sensor sensor : sensorList){
			try {
				
				//2.1- Running the sensor service
				/*Runtime.getRuntime().exec("java -jar " + sensor.getSensorFile() + 
											" --server.port=" + sensor.getSensorPort().toString() + 
											" --management.server.port=" + sensor.getManagementPort().toString());*/
				this.sensorList.add(sensor);
				Thread.sleep(this.waitSensorStart);
				
				//Triggering the action
				this.applicationEventPublisher.publishEvent(new GenericArtieEvent(this, SensorActionEnum.RUN.toString(), sensor.getSensorName(), true));
				
				//Logging the action
				this.logger.debug("Sensor - " + SensorActionEnum.RUN.toString() + " - " + sensor.getSensorName() + " - OK");
				
				//2.2- Getting the configuration from the sensor
				String jsonSensorConfiguration = this.restTemplate.getForObject("http://localhost:" + sensor.getSensorPort() + "/artie/sensor/" + sensor.getSensorName() + "/getConfiguration", String.class);
				
				//convert JSON string to Map
				sensorConfiguration = mapper.readValue(jsonSensorConfiguration, new TypeReference<HashMap<String,String>>(){});

				
				//2.2- Sets the parameters values in the sensor configuration
				sensorConfiguration.replace(ConfigurationEnum.DB_URL.toString(), this.sensorDataSourceUrl);
				sensorConfiguration.replace(ConfigurationEnum.DB_DRIVER_CLASS.toString(), this.dataSourceDriver);
				sensorConfiguration.replace(ConfigurationEnum.DB_USER.toString(), this.dataSourceUser);
				sensorConfiguration.replace(ConfigurationEnum.DB_PASSWD.toString(), this.dataSourcePasswd);
				
				
				//2.3- Sets the new parameters in the sensor configuration
				jsonSensorConfiguration = mapper.writeValueAsString(sensorConfiguration);
				this.restTemplate.postForObject("http://localhost:" + sensor.getSensorPort() + "/artie/sensor/" + sensor.getSensorName() + "/configuration", jsonSensorConfiguration, String.class);
				
				//2.4- Starting the sensor
				this.restTemplate.getForEntity("http://localhost:" + sensor.getSensorPort() + "/artie/sensor/" + sensor.getSensorName() + "/start", String.class);
				
				//Triggering the action
				this.applicationEventPublisher.publishEvent(new GenericArtieEvent(this, SensorActionEnum.START.toString(), sensor.getSensorName(), true));
				
				//Logging the action
				this.logger.debug("Sensor - " + SensorActionEnum.START.toString() + " - " + sensor.getSensorName() + " - OK");
				
			} catch (Exception e) {
				this.logger.error(e.getMessage());
			}
		}
		
		//Loading process finished
		this.loadingProcessFinished = true;
	}
	
}
