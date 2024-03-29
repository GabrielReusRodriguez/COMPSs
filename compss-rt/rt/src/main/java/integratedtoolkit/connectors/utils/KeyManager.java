/*
 *  Copyright 2002-2014 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */



package integratedtoolkit.connectors.utils;


public class KeyManager {

    private static String keyPair=null;
    private static String keyType=null;
    private static String publicKey=null;
    private static String privateKey=null;

    public static String getKeyType(){
        if (keyType!=null) return keyType;
        getKeyPair();
        return keyType;
    }

    public static String getKeyPair(){
        if (keyPair!=null) return keyPair;
        String keyfile = null;
        String keyfilePub = null;
        String home = System.getProperty("user.home");
        String fileSep = System.getProperty("file.separator");

        if (home == null) {
            home = "";
        } else {
            home += fileSep;
        }

        keyfile = home + ".ssh" + fileSep + "id_dsa";
        java.io.File keyf = new java.io.File(keyfile);
        keyfilePub = home + ".ssh" + fileSep + "id_dsa.pub";
        java.io.File keyfpub = new java.io.File(keyfilePub);
        keyType="id_dsa";
        if (!keyf.exists() || !keyfpub.exists()) {
            keyfile = home + ".ssh" + fileSep + "id_rsa";
            keyf = new java.io.File(keyfile);
            keyfilePub = home + ".ssh" + fileSep + "id_rsa.pub";
            keyfpub = new java.io.File(keyfilePub);
            keyType="id_rsa";
            if (!keyf.exists() || !keyfpub.exists()) {
                keyfile = home + ".ssh" + fileSep + "identity";
                keyf = new java.io.File(keyfile);
                keyfilePub = home + ".ssh" + fileSep + "identity.pub";
                keyfpub = new java.io.File(keyfilePub);
                keyType="identity";
                if (!keyf.exists() || !keyfpub.exists()) {
                    keyfile = home + "ssh" + fileSep + "id_dsa";
                    keyf = new java.io.File(keyfile);
                    keyfilePub = home + "ssh" + fileSep + "id_dsa.pub";
                    keyfpub = new java.io.File(keyfilePub);
                    keyType="id_dsa";
                    if (!keyf.exists() || !keyfpub.exists()) {
                        keyfile = home + "ssh" + fileSep + "id_rsa";
                        keyf = new java.io.File(keyfile);
                        keyfilePub = home + "ssh" + fileSep + "id_rsa.pub";
                        keyfpub = new java.io.File(keyfilePub);
                        keyType="id_rsa";
                        if (!keyf.exists() || !keyfpub.exists()) {
                            keyfile = home + "ssh" + fileSep + "identity";
                            keyf = new java.io.File(keyfile);
                            keyfilePub = home + "ssh" + fileSep + "identity.pub";
                            keyfpub = new java.io.File(keyfilePub);
                            keyType="identity";
                            if (!keyf.exists() || !keyfpub.exists()) {
                                return null;
                            }
                        }
                    }
                }
            }
        }
        keyPair=keyfile;
        return keyfile;
    }


    public static String getPublicKey(String keyfile) throws Exception{
        if (publicKey!=null)return publicKey;
        java.io.BufferedReader input =  new java.io.BufferedReader(new java.io.FileReader(keyfile+".pub"));
        
        StringBuilder key= new StringBuilder();
        String sb=input.readLine();
        while (sb!=null){
            key.append(sb).append("\n");
            sb=input.readLine();
        }
        publicKey=key.toString();
        return key.toString();
    }

    public static String getPrivateKey(String keyfile) throws Exception{
        if (privateKey!=null)return privateKey;

        java.io.BufferedReader input =  new java.io.BufferedReader(new java.io.FileReader(keyfile));
        
        StringBuilder key= new StringBuilder();
        String sb=input.readLine();
        while (sb!=null){
            key.append(sb).append("\n");
            sb=input.readLine();
        }
        privateKey=key.toString();
        return key.toString();
    }

}
