package com.synapse.backend.services;

import com.synapse.backend.models.CsvDocument;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CsvStorageService {

    private final Map<String, CsvDocument> documents = new ConcurrentHashMap<>();

    public CsvDocument save(CsvDocument document) {
        documents.put(document.getId(), document);
        return document;
    }

    public Optional<CsvDocument> getById(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    public List<CsvDocument> getAll() {
        return new ArrayList<>(documents.values());
    }

    public boolean delete(String id) {
        return documents.remove(id) != null;
    }
}
