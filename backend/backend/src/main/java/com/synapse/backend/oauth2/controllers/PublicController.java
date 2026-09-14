package com.synapse.backend.oauth2.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.swing.text.html.parser.Entity;

@RestController
public class PublicController {
    @GetMapping("/app")
    public ResponseEntity<> publicMessage(){
        return ResponseEntity.ok();
    }
}
