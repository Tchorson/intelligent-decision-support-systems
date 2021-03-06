package com.tchorek.routes_collector.monitoring.controller;

import com.tchorek.routes_collector.database.json.RegistrationData;
import com.tchorek.routes_collector.database.model.Fugitive;
import com.tchorek.routes_collector.database.model.Registration;
import com.tchorek.routes_collector.database.service.DatabaseService;
import com.tchorek.routes_collector.encryption.Encryptor;
import com.tchorek.routes_collector.encryption.EncryptorProperties;
import com.tchorek.routes_collector.message.service.MessageService;
import com.tchorek.routes_collector.monitoring.service.DataMonitoringService;
import com.tchorek.routes_collector.monitoring.service.LoginService;
import com.tchorek.routes_collector.utils.Mapper;
import com.tchorek.routes_collector.utils.Timer;
import com.tchorek.routes_collector.utils.Validator;
import javassist.NotFoundException;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.management.openmbean.KeyAlreadyExistsException;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@Log4j2
@Controller
@CrossOrigin
public class DataMonitoringController {

    @Autowired
    DataMonitoringService dataMonitoringService;

    @Autowired
    DatabaseService databaseService;

    @Autowired
    LoginService loginService;

    @Autowired
    EncryptorProperties encryptorProperties;

    @Autowired
    MessageService messageService;

    @Autowired
    Validator validator;

    @ResponseBody
    @PostMapping(path = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity verifyUserLocation(@RequestBody RegistrationData verificationData) { //Todo: Refactor encryption/decryption mechanism, add user token
        verificationData.setUserData(decryptUser(verificationData.getUserData()));
        try {
            if (dataMonitoringService.isUserAtHome(verificationData)) return ResponseEntity.ok(HttpStatus.OK);
            else {
                databaseService.saveNewFugitiveInDB(verificationData);
                dataMonitoringService.addNewFugitive(verificationData.getUserData());
                return ResponseEntity.ok().body("USER " + verificationData.getUserData() + "" +
                        " IN UNKNOWN AREA at time " + Timer.getFullDate(verificationData.getDate()));
            }
        } catch (Exception e) {
            if (e instanceof NotFoundException) {
                log.warn("USER NOT FOUND IN DATABASE");
                return ResponseEntity.ok(HttpStatus.NOT_FOUND);
            }
            if (e instanceof KeyAlreadyExistsException) {
                log.warn("FUGITIVE ALREADY IN SYSTEM");
                return ResponseEntity.ok(HttpStatus.FOUND);
            }

            log.warn("PLEASE CONTACT SANEPID IMMEDIATELY FOR {}", verificationData.getUserData());
            return ResponseEntity.ok(HttpStatus.UNAUTHORIZED);
        }
    }

    @PostMapping(path = "/report", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity reportFugitive(@RequestBody String fugitive, @RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        try {
            dataMonitoringService.removeFugitiveFromService(decryptUser(fugitive));
            return ResponseEntity.ok(HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.ok(HttpStatus.NOT_FOUND);
        }
    }

    @ResponseBody
    @GetMapping(path = "/missing-users")
    public ResponseEntity getUsersWithUnknownStatus(@RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        Set<String> missingUsers = dataMonitoringService.getAllMissingUsers();
        Set<String> encryptedMissingUsers = new LinkedHashSet<>();
        missingUsers.forEach(user -> encryptedMissingUsers.add(encryptUser(user)));
        return ResponseEntity.ok().body(encryptedMissingUsers);
    }

    @PutMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity registerUser(@RequestBody RegistrationData registration) {
        try{
            registration.setUserData(decryptRegistration(registration.getUserData()));
            if(registration.getUserData().isEmpty() || registration.getUserData().isBlank() || !validator.isNumberValid(registration.getUserData()))
                return ResponseEntity.ok(HttpStatus.CONFLICT);
            Registration mappedObj = Mapper.mapJsonToObject(registration);
            databaseService.saveRegistration(mappedObj);
            return ResponseEntity.ok(HttpStatus.OK);
        } catch (Exception e){
            return ResponseEntity.ok(HttpStatus.CONFLICT);
        }
    }

    @GetMapping(path = "/registrations")
    public ResponseEntity getAllRegisteredUsers(@RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        Iterable<Registration> registrations = databaseService.getAllRegisteredUsers();
        registrations.forEach(registration -> registration.setPhoneNumber(encryptUser(registration.getPhoneNumber())));
        return ResponseEntity.ok().body(registrations);
    }

    @PostMapping(path = "/send-approval-decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity sendAllDecisionsToDB(@RequestBody Registration[] decisions, @RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        for (Registration decision : decisions) {
            decision.setPhoneNumber(decryptUser(decision.getPhoneNumber()));
            if (databaseService.isApprovalInDB(decision)) {
                log.info("APPROVING {}", decision.getPhoneNumber());
                //Todo: Send sms service depending on whether the timestamp has been altered or not
                databaseService.saveRegistration(decision);
                dataMonitoringService.logRegistration(decision);
            } else
                log.warn("DENIED APPROVAL FOR UNKNOWN USER {}", decision.getPhoneNumber());
        }
        return ResponseEntity.ok(HttpStatus.OK);
    }

    @GetMapping(path = "/get-users-without-decision", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity getAllNewApprovals(@RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        List<Registration> registrations = databaseService.getAllNewRegistrations();
        registrations.forEach(registration -> registration.setPhoneNumber(encryptUser(registration.getPhoneNumber())));
        return ResponseEntity.ok().body(registrations);
    }

    @GetMapping(path = "/get-all-approved-users")
    public ResponseEntity getAllApprovedUsersToLeave(@RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        Set<String> users = databaseService.getAllApprovedUsers();
        users.forEach(this::encryptUser);
        return ResponseEntity.ok().body(users);
    }

    @GetMapping(path = "/get-all-fugitives")
    public ResponseEntity getAllFugitives(@RequestParam(name = "token") String token) {
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        Iterable<Fugitive> fugitives = databaseService.getAllFugitives();
        fugitives.forEach(fugitive -> fugitive.setPhoneNumber(encryptUser(fugitive.getPhoneNumber())));
        return ResponseEntity.ok().body(fugitives);
    }

    private String encryptUser(String data) {
        return Encryptor.encrypt(data, encryptorProperties.getKey(), encryptorProperties.getIv());
    }

    private String decryptUser(String data) {
        return Encryptor.decrypt(data, encryptorProperties.getKey(), encryptorProperties.getIv());
    }

    private String decryptRegistration(String data){
        return Encryptor.decrypt(data, encryptorProperties.getKeyRegistration(), encryptorProperties.getIvRegistration());
    }

    @PostMapping(path= "/alert")
    public ResponseEntity reportFugitives(@RequestBody Fugitive[] fugitivesToReport, @RequestParam(name = "token") String token){
        if (!loginService.isTokenValid(token)) {
            return ResponseEntity.ok(HttpStatus.FORBIDDEN);
        }
        log.info("Reporting fugitives:");
        List<String> fugitives = new LinkedList<>();
        for (Fugitive fugitive: fugitivesToReport){
            fugitive.setPhoneNumber(decryptUser(fugitive.getPhoneNumber()));
            log.info("{}",fugitive.toString());
            fugitives.add(fugitive.getPhoneNumber());
        }
        databaseService.markReportedFugitives(fugitivesToReport);
        messageService.prepareAndSendEmail("List of today's fugitives", fugitives.toString());
        return ResponseEntity.ok(HttpStatus.OK);
    }
}
