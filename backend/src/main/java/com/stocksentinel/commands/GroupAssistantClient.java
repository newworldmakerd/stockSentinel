package com.stocksentinel.commands;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public interface GroupAssistantClient {
    @FunctionalInterface interface Tools {Object execute(String name,JsonNode arguments);}
    String reply(List<Map<String,Object>> conversation,Tools tools) throws Exception;
}
