package edu.uri.dfcsc.occp.generator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import edu.uri.dfcsc.occp.OccpAdmin;
import edu.uri.dfcsc.occp.OccpParser.OccpVariables;

/**
 * Work in progress - Used to generate usernames
 * Accepted parameters:
 * count - A positive non-zero integer representing the number of passwords to generate. Default is 1. (Optional)
 * names - The name CSV to use from the scenario's base directory. (Required)
 * TODO Take list of reserved names
 */
public class UsernameGenerator extends Generator {

    @Override
    public OccpVariables generate(String variableName, HashMap<String, String> parameters)
            throws InvalidGeneratorParameterException {
        OccpVariables result = new OccpVariables();

        int count = this.getCount(parameters);

        // Look for the csv
        String nameFile = this.getStringValue("names", parameters, "", true);

        ArrayList<User> users = this.generateRandomUsers(count, OccpAdmin.scenarioBaseDir + "/" + nameFile);

        if (users.size() == count) {
            // No issues, lets go through and add these as variables

            if (count > 1) {
                // Multiple users, index them
                ArrayList<String> firstNames = new ArrayList<>();
                ArrayList<String> lastNames = new ArrayList<>();
                ArrayList<String> usernames = new ArrayList<>();
                for (User user : users) {
                    firstNames.add(user.getFirstName());
                    lastNames.add(user.getLastName());
                    usernames.add(user.getUsername());
                }
                result.setVariable(variableName + "_first", firstNames);
                result.setVariable(variableName + "_last", lastNames);
                result.setVariable(variableName, usernames);
            } else {
                // Only one user, do not index
                User user = users.get(0);
                result.setVariable(variableName + "_first", user.getFirstName());
                result.setVariable(variableName + "_last", user.getLastName());
                result.setVariable(variableName, user.getUsername());
            }

        } else if (users.size() > 0 && users.size() < count) {
            // Not enough valid usernames to pick from in order to generate the requested amount
            throw new InvalidGeneratorParameterException(
                    this.getGeneratorName()
                            + " generator was unable to generate the requested amount of names, try increasing the sample size in the names file or requesting less names.");
        } else {
            // Indicates a problem with the file
            throw new InvalidGeneratorParameterException(this.getGeneratorName()
                    + " generator was unable to open the file specified by the names parameter.");
        }

        return result;
    }

    /**
     * Generate a list of random users from the specified csv file. If the amount of usernames in the csv is not
     * sufficient for the amount requested a random shuffling of the usernames available are returned. If there are
     * problems with the file a list of size zero is returned.
     * CSV should of the form:
     * first,last,username
     * 
     * @param amount The amount of users to pick
     * @param csvOfNames Filename of a username csv
     * @return An ArrayList of OCCPUsers
     */
    private ArrayList<User> generateRandomUsers(int amount, String csvOfNames) {
        return generateRandomUsers(amount, csvOfNames, null);
    }

    /**
     * Generate a list of random users from the specified csv file that do not match a given list. If the amount of
     * usernames in the csv is not sufficient for the amount requested a random shuffling of the usernames available are
     * returned. If there are problems with the file a list of size zero is returned.
     * CSV should of the form:
     * first,last,username
     * 
     * @param amount The amount of users to pick
     * @param csvOfNames Filename of a username csv
     * @param reservedNames a list of names that cannot be used
     * @return An ArrayList of OCCPUsers
     */
    private ArrayList<User> generateRandomUsers(int amount, String csvOfNames, ArrayList<String> reservedNames) {
        ArrayList<User> result = new ArrayList<>();
        String line;

        // First generate a pool of acceptable users. i.e. do not match a reserved name
        ArrayList<User> pool = new ArrayList<>();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(csvOfNames))) {
            while ((line = bufferedReader.readLine()) != null) {
                String[] temp = line.split(",");
                if (temp.length == 3) {
                    // Expected first, last, username
                    if (reservedNames != null) {
                        // Ensure we do not match a reserved name
                        if (!reservedNames.contains(temp[2])) {
                            // We don't match one so add to our pool
                            pool.add(new User(temp[0], temp[1], temp[2]));
                        }
                    } else {
                        // There are no restrictions, add to the pool
                        pool.add(new User(temp[0], temp[1], temp[2]));
                    }
                }
            }
        } catch (IOException e) {
            // Nothing we can do, caller will get list of size zero
        }

        // Shuffle the pool
        Collections.shuffle(pool);

        if (amount < pool.size()) {
            // We can choose the specified amount, so pick that many for the caller
            for (int i = 0; i < amount; i++) {
                result.add(pool.get(i));
            }
        } else {
            // Pool is not large enough to pick the requested number, return the pool
            result = pool;
        }
        return result;
    }

    /**
     * Data structure to hold user name info
     */
    private final static class User {
        private final String firstName, lastName, username;

        public User(String firstName, String lastName, String username) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.username = username;
        }

        public String getFirstName() {
            return this.firstName;
        }

        public String getLastName() {
            return this.lastName;
        }

        public String getUsername() {
            return this.username;
        }

        @Override
        public String toString() {
            return this.getFirstName() + " " + this.getLastName() + ": " + this.getUsername();
        }
    };

    @Override
    public String getGeneratorName() {
        return "username";
    }
}
