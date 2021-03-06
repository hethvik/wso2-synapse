/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.synapse.commons.json.jsonprocessor.validators;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.synapse.commons.json.jsonprocessor.constants.ValidatorConstants;
import org.apache.synapse.commons.json.jsonprocessor.exceptions.ParserException;
import org.apache.synapse.commons.json.jsonprocessor.exceptions.ValidatorException;
import org.apache.synapse.commons.json.jsonprocessor.utils.DataTypeConverter;
import org.apache.synapse.commons.json.jsonprocessor.utils.GSONDataTypeConverter;
import org.apache.synapse.commons.json.jsonprocessor.utils.JsonProcessorUtils;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * This class will validate json arrays according to the schema.
 */
public class ArrayValidator {

    private static final String MIN_ITEMS = "minItems";
    private static final String MAX_ITEMS = "maxItems";
    private static final String ITEMS = "items";
    private static final String UNIQUE_ITEMS = "uniqueItems";
    private static final String ADDITIONAL_ITEMS = "additionalItems";

    // Use without instantiation
    private ArrayValidator() {

    }

    /**
     * This method will validates an input array according to a given schema.
     *
     * @param input  input array as a Map.
     * @param schema JSON schema as an object.
     * @return Validated JSON array.
     * @throws ValidatorException Exception occurs in validation process.
     * @throws ParserException    Exception occurs in data type parsing.
     */
    public static JsonArray validateArray(Map.Entry<String, JsonElement> input, JsonObject
            schema) throws ValidatorException, ParserException {

        JsonParser parser;
        int minItems = -1;
        int maxItems = -1;
        boolean uniqueItems = false;
        boolean notAllowAdditional = false;
        // parsing the properties related to arrays from the schema, if they exists.
        if (schema.has(UNIQUE_ITEMS)) {
            String uniqueItemsString = JsonProcessorUtils.replaceEnclosingQuotes(
                    schema.get(UNIQUE_ITEMS).getAsString());
            if (!uniqueItemsString.isEmpty()) {
                uniqueItems = DataTypeConverter.convertToBoolean(uniqueItemsString);
            }
        }
        if (schema.has(MIN_ITEMS)) {
            String minItemsString = JsonProcessorUtils.replaceEnclosingQuotes(schema.get(MIN_ITEMS).getAsString());
            if (!minItemsString.isEmpty()) {
                minItems = DataTypeConverter.convertToInt(minItemsString);
                if (minItems < 0) {
                    throw new ValidatorException("Invalid minItems constraint in the schema");
                }
            }
        }
        if (schema.has(MAX_ITEMS)) {
            String maxItemsString = JsonProcessorUtils.replaceEnclosingQuotes(schema.get(MAX_ITEMS).getAsString());
            if (!maxItemsString.isEmpty()) {
                maxItems = DataTypeConverter.convertToInt(maxItemsString);
                if (maxItems < 0) {
                    throw new ValidatorException("Invalid maxItems constraint in the schema");
                }
            }
        }

        // parsing additionalItems
        // We are wrapping the additionalItems schema inside a json array schema object and calling this same method
        // again
        JsonObject additionalItemsSchema = null;
        if (schema.has(ADDITIONAL_ITEMS)) {
            JsonElement tempElement = schema.get(ADDITIONAL_ITEMS);
            if (tempElement.isJsonPrimitive() && !tempElement.getAsBoolean()) {
                notAllowAdditional = true;
            } else if (tempElement.isJsonObject() && !tempElement.getAsJsonObject().entrySet().isEmpty()) {
                StringBuffer jsonString = new StringBuffer("{\"type\": \"array\",\"items\": ");
                jsonString.append(schema.get(ADDITIONAL_ITEMS).toString());
                jsonString.append("}");
                parser = new JsonParser();
                additionalItemsSchema = parser.parse(jsonString.toString()).getAsJsonObject();
            }
        }

        // Convert the input to an array. If possible, do the single element array correction. Ex 45 -> [45]
        // else throw an error
        JsonArray inputArray;
        if (input.getValue().isJsonArray()) {
            inputArray = input.getValue().getAsJsonArray();
        } else {
            inputArray = singleElementArrayCorrection(input.getValue());
        }

        // Structural validations
        doStructuralValidations(inputArray, minItems, maxItems, uniqueItems);

        // processing the items property in JSON array.
        if (schema.has(ITEMS)) {
            // Items must be either a valid JSON Schema or an array of valid JSON Schemas.
            if (schema.get(ITEMS).isJsonArray()) {
                // Items - valid JSON array.
                JsonArray schemaArray = schema.get(ITEMS).getAsJsonArray();
                processSchemaWithItemsArray(inputArray, schemaArray, additionalItemsSchema, notAllowAdditional);
                // take all instances from json array and iteratively validate them.
            } else if (schema.get(ITEMS).isJsonObject()) {
                // Item is a JSON object
                JsonObject schemaObject = schema.get(ITEMS).getAsJsonObject();
                processSchemaWithOneItem(inputArray, schemaObject);
            } else {
                throw new ValidatorException("Schema for Array is invalid. " +
                        "Should contain either JsonArray or JsonObject");
            }
        }
        return inputArray;
    }

    /**
     * JSON structure correction. Convert single elements to arrays.
     *
     * @param element JsonElement payload.
     * @return Json array.
     */
    private static JsonArray singleElementArrayCorrection(JsonElement element) {

        JsonArray array = new JsonArray();
        array.add(element);
        return array;
    }

    /**
     * Validate JSON array when both items and schema are arrays.
     * Ex:- {"type":"array", "items":[{"type": "boolean"},{"type": "numeric"}]}
     *
     * @param inputArray  input data as json array.
     * @param schemaArray inout schema as json array.
     * @throws ValidatorException validation exception occurs.
     * @throws ParserException    parsing exception occurs.
     */
    private static void processSchemaWithItemsArray(JsonArray inputArray, JsonArray schemaArray, JsonObject
            additionalItemsSchema, boolean notAllowAdditional) throws ValidatorException, ParserException {

        if (notAllowAdditional && inputArray.size() > schemaArray.size()) {
            throw new ValidatorException(
                    "Array : " + inputArray.toString() + " has more items than allowed in the schema");
        }
        int i = 0;
        for (JsonElement element : schemaArray) {
            JsonObject tempObj = element.getAsJsonObject();
            // Checking for empty input schema Ex:- {}
            if (!tempObj.entrySet().isEmpty()) {
                if (tempObj.has(ValidatorConstants.TYPE_KEY)) {
                    String type = JsonProcessorUtils.replaceEnclosingQuotes(tempObj.get(ValidatorConstants.TYPE_KEY).toString());
                    if (ValidatorConstants.BOOLEAN_KEYS.contains(type)) {
                        inputArray.set(i, BooleanValidator.validateBoolean(tempObj, inputArray.get(i).getAsString()));
                    } else if (ValidatorConstants.NOMINAL_KEYS.contains(type)) {
                        inputArray.set(i, StringValidator.validateNominal(tempObj, inputArray.get(i).getAsString()));
                    } else if (ValidatorConstants.NUMERIC_KEYS.contains(type)) {
                        inputArray.set(i, NumericValidator.validateNumeric(tempObj, inputArray.get(i).getAsString()));
                    } else if (ValidatorConstants.ARRAY_KEYS.contains(type)) {
                        inputArray.set(i, ArrayValidator.validateArray(
                                GSONDataTypeConverter.getMapFromString(inputArray.get(i).toString()), tempObj));
                    } else if (ValidatorConstants.NULL_KEYS.contains(type)) {
                        if (inputArray.get(i) != null) {
                            NullValidator.validateNull(tempObj, inputArray.get(i).toString());
                        }
                        inputArray.set(i, JsonNull.INSTANCE);
                    } else if (ValidatorConstants.OBJECT_KEYS.contains(type)) {
                        inputArray.set(i, ObjectValidator.validateObject(inputArray.get(i).getAsJsonObject(), tempObj));
                    }
                } else {
                    throw new ValidatorException("Array items should contain a type " +
                            "declaration");
                }
            }
            i++;
        }
        // additional schema validating the reset of the array
        if (additionalItemsSchema != null && inputArray.size() > schemaArray.size()) {
            JsonArray extraArray = new JsonArray();
            for (int j = i; j < inputArray.size(); j++) {
                extraArray.add(inputArray.get(j));
            }
            extraArray = ArrayValidator.validateArray(GSONDataTypeConverter.getMapFromJsonArray(extraArray),
                    additionalItemsSchema);
            // putting back the values again after validation
            for (int j = 0; j < extraArray.size(); j++) {
                inputArray.set(i + j, extraArray.get(j));
            }
        }
    }

    /**
     * Validate JSON array when items is a single JSON object.
     * Ex:- {"type":"array", "items":{"type": "boolean"}}
     *
     * @param inputArray   input data as json array.
     * @param schemaObject input schema as json object.
     * @throws ValidatorException validation exception occurs.
     * @throws ParserException    parsing exception occurs.
     */
    private static void processSchemaWithOneItem(JsonArray inputArray, JsonObject schemaObject) throws
            ValidatorException, ParserException {
        if (schemaObject.has(ValidatorConstants.TYPE_KEY)) {
            String type = JsonProcessorUtils.replaceEnclosingQuotes(
                    schemaObject.get(ValidatorConstants.TYPE_KEY).toString());
            int i = 0;
            if (ValidatorConstants.BOOLEAN_KEYS.contains(type)) {
                for (JsonElement element : inputArray) {
                    inputArray.set(i, BooleanValidator.validateBoolean(schemaObject, element.getAsString()));
                    i++;
                }
            } else if (ValidatorConstants.NUMERIC_KEYS.contains(type)) {
                for (JsonElement element : inputArray) {
                    inputArray.set(i, NumericValidator.validateNumeric(schemaObject, element.getAsString()));
                    i++;
                }
            } else if (ValidatorConstants.NOMINAL_KEYS.contains(type)) {
                for (JsonElement element : inputArray) {
                    inputArray.set(i, StringValidator.validateNominal(schemaObject, element.getAsString()));
                    i++;
                }
            } else if (ValidatorConstants.ARRAY_KEYS.contains(type)) {
                for (JsonElement element : inputArray) {
                    inputArray.set(i, ArrayValidator.validateArray(GSONDataTypeConverter.getMapFromString(
                            element.getAsString()), schemaObject));
                    i++;
                }
            } else if (ValidatorConstants.OBJECT_KEYS.contains(type)) {
                for (JsonElement element : inputArray) {
                    inputArray.set(i, ObjectValidator.validateObject(element.getAsJsonObject(), schemaObject));
                    i++;
                }
            } else if (ValidatorConstants.NULL_KEYS.contains(type)) {
                for (JsonElement element : inputArray) {
                    if (element != null) {
                        NullValidator.validateNull(schemaObject, element.toString());
                    }
                    inputArray.set(i, JsonNull.INSTANCE);
                    i++;
                }
            } else {
                throw new ValidatorException("Schema for array must have a type declaration" +
                        schemaObject.toString());
            }
        }
    }

    /**
     * This method validates the structure of given JSON array against constraints.
     *
     * @param inputArray  input JSON array.
     * @param minItems    minimum items allowed.
     * @param maxItems    maximum items allowed.
     * @param uniqueItems array items should be unique.
     * @throws ValidatorException validation exception occurs.
     */
    private static void doStructuralValidations(JsonArray inputArray, int minItems, int maxItems, boolean
            uniqueItems) throws ValidatorException {

        final String errorMsg = "Error occurs while validating the structure of array : ";
        if (minItems != -1 && inputArray.size() < minItems) {
            throw new ValidatorException(errorMsg + inputArray.toString() +
                    ". Array violated the minItems constraint");
        }
        if (maxItems != -1 && inputArray.size() > maxItems) {
            throw new ValidatorException(errorMsg + inputArray.toString() +
                    ". Array violated the maxItems constraint");
        }
        if (uniqueItems) {
            Set<JsonElement> temporarySet = new HashSet<>();
            for (JsonElement element : inputArray) {
                if (!temporarySet.add(element)) {
                    throw new ValidatorException(errorMsg +
                            inputArray.toString() + ". Array violated the uniqueItems constraint");
                }
            }
        }
    }
}
