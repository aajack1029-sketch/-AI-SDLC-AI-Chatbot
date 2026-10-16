package com.example.demo;

import java.util.List;

public interface IntentClassifier {

    List<IntentCategory> classify(String userQuery);
}
