package server;

import com.google.gson.Gson;
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.MongoClient;

import org.bson.*;

//import org.slf4j.Logger;

//import org.slf4j.LoggerFactory;

import utils.Event;
import utils.SocketUtils;
import utils.User;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentSkipListMap;

public class ServerWithMongoTemplate extends MultiThreadServer implements Runnable {

    private String name;
    private MongoCollection<Document> users;
    // private ConcurrentSkipListMap<String, User> users; // Name -> User
    // private ConcurrentSkipListMap<String, String> mails; // Mail -> Name

    private Database db; // Esto sustituye a users y mails

    // Socket -> Write (One per user)
    // private ConcurrentSkipListMap<String, PrintWriter> socketWriter;
    private ConcurrentSkipListMap<String, ObjectOutputStream> socketWriter;

    // Constructor
    public ServerWithMongoTemplate(String name) {

        super(8080);
        this.name = name;
        // users = new ConcurrentSkipListMap<>();
        // users.put("admin", new User("admin@uma.es", "admin", "1234", "0", true));
        // mails = new ConcurrentSkipListMap<>();
        socketWriter = new ConcurrentSkipListMap<>();

        db = Database.getInstance(); // Esto sustituye a users y mails
        db.addUser(new User("admin@uma.es", "admin", "1234", "0", true));

        ConnectionString connString = new ConnectionString(
                "mongodb+srv://software:MyReminder@cluster0.cb2w0.mongodb.net/Software?w=majority");
        MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(connString).retryWrites(true)
                .build();
        MongoClient mongoClient = MongoClients.create(settings);
        MongoDatabase database = mongoClient.getDatabase("Software");
        users = database.getCollection("Users");

        // Caching process
        FindIterable<Document> dbUsers = users.find();
        for (Document user : dbUsers) {
            final Gson gson = new Gson();
            final User userInstance = gson.fromJson(user.toJson(), User.class);
            //db.addUser(user.getObjectId("_id").toString(), userInstance);
        }

    }

    @Override
    public void handleConnection(Socket connection) throws IOException {

        ObjectOutputStream output = SocketUtils.getWriter(connection);
        ObjectInputStream input = SocketUtils.getReader(connection);

        System.out.println("Connection Local Port: " + connection.getLocalPort());
        System.out.println("Connection Remote Port: " + connection.getPort());
        System.out.println("Host: " + connection.getInetAddress().getHostName());

        String line;
        User user;
        Event event;

        try {
            while ((line = (String) input.readObject()) != null) {
                if (!line.equalsIgnoreCase("")) {
                    switch (line.toUpperCase()) {
                        case "SIGN UP":
                            user = (User) input.readObject();
                            signUp(user, output);
                            break;
                        case "SIGN IN":
                            user = (User) input.readObject();
                            signIn(user, output);
                            break;
                        case "CREATE EVENT":
                            event = (Event) input.readObject();
                            createEvent(event, output);
                            break;
                        case "INVITATION":
                            event = (Event) input.readObject();
                            sendInvitation(event, output);
                            break;
                        case "ANSWER INVITATION":
                            event = (Event) input.readObject();
                            answerInvitation(event, output);
                            break;
                        case "FORGOTTEN PASSWORD":
                            User aux;
                            user = (User) input.readObject();
                            String name;
                            // if (mails.containsKey(user.getMail())) {
                            if (db.containsMail(user.getMail())) {
                                // name = mails.get(user.getMail());
                                name = db.getUserName(user.getMail());
                                // aux = users.get(name);
                                aux = db.getUser(name);
                                if (user.getDni().equals(aux.getDni())) {
                                    aux.setPassword(user.getPassword());
                                }
                            }
                            break;
                        default:
                            throw new RuntimeException("Unknown command!");
                    }
                }
            }
        } catch (SocketException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        // Nunca se cerrar?? la conexi??n en principio
        connection.close();

    }

    @Override
    public void run() {
        listen();
    }

    // -----------------------------------------------------------------------------------
    // -------------------------------- FUNCIONES CREADAS
    // --------------------------------
    // -----------------------------------------------------------------------------------

    private void signUp(User user, ObjectOutputStream output) throws IOException {
        // Compruebo que el mail y el nombre de usuario utilizado no existan
        // if (mails.containsKey(user.getMail()) || users.containsKey(user.getName())) {
        if (db.containsMail(user.getMail()) || db.containsUserName(user.getName())) {
            output.writeObject("Sign up: Error. User already exists");
        } else {
            // Introduzco el usuario y el mail
            // users.put(user.getName(), user);
            db.addUser(user);
            // mails.put(user.getMail(), user.getName());
            db.addMail(user.getMail(), user.getName());
            output.writeObject("Sign up: OK");
        }
    }

    private void signIn(User user, ObjectOutputStream output) throws IOException {
        // Compruebo que el nombre de usuario existe
        // if (!users.containsKey(user.getName())) {
        if (!db.containsUserName(user.getName())) {
            output.writeObject("Sign in: Error. User doesn't exist");
        } else {
            // User userAux = users.get(user.getName());
            User userAux = db.getUser(user.getName());
            // Compruebo que la contrase??a coincide
            if (user.correctPassword(userAux.getPassword())) {
                // Introduzco el Writer y le env??o la informaci??n completa del usuario
                socketWriter.put(user.getName(), output);
                output.writeObject("Sign in: OK");
                output.writeObject(userAux);
            } else {
                output.writeObject("Sign in: Error. Incorrect password");
            }
        }
    }

    private void createEvent(Event event, ObjectOutputStream output) throws IOException {
        // Introduzco el evento
        // User user = users.get(event.getOwner());
        User user = db.getUser(event.getOwner());
        user.putEvent(event);
        output.writeObject("Create event: OK");
        output.writeObject(event);
        // Compruebo los invitados, actualizo sus eventos y les env??o un mensaje si
        // est??n activos
        for (String guestName : event.getGuests().keySet()) {
            // if (users.containsKey(guestName)) {
            if (db.containsUserName(guestName)) {
                // user = users.get(guestName);
                user = db.getUser(guestName);
                user.putEvent(event);
                if (socketWriter.containsKey(guestName)) {
                    // Se puede enviar el evento y que el cliente lo actualice
                    socketWriter.get(guestName).writeObject("Invitation");
                    socketWriter.get(guestName).writeObject(event);
                    /*
                     * // Tambien se puede enviar el usuario completo actualizado y el cliente
                     * simplemente lo copia socketWriter.get(name).writeObject("Invitation");
                     * socketWriter.get(name).writeObject(user);
                     */
                }
            }
        }
    }

    private void sendInvitation(Event event, ObjectOutputStream output) throws IOException {
        // Comparo los nuevos invitados con los que hab??a anteriormente
        // Event eventAux = users.get(event.getOwner()).getEvent(event.getId());
        Event eventAux = db.getUser(event.getOwner()).getEvent(event.getId());
        for (String guestName : event.getGuests().keySet()) {
            // Si hay algun invitado nuevo le envio un mensaje si est?? activo
            if (!eventAux.containsGuest(guestName)) {
                if (socketWriter.containsKey(guestName)) {
                    // Se puede enviar el evento y que el cliente lo actualice
                    socketWriter.get(guestName).writeObject("Invitation");
                    socketWriter.get(guestName).writeObject(event);
                    /*
                     * // Tambien se puede enviar el usuario completo actualizado y el cliente
                     * simplemente lo copia socketWriter.get(name).writeObject("Invitation");
                     * socketWriter.get(name).writeObject(user);
                     */
                }
            }
        }
        // Actualizo los invitados
        eventAux.setGuests(event.getGuests());
    }

    private void answerInvitation(Event event, ObjectOutputStream output) throws IOException {
        // Actualizo el evento del propietario y de los invitados
        // users.get(event.getOwner()).putEvent(event);
        db.getUser(event.getOwner()).putEvent(event);
        for (String guestName : event.getGuests().keySet()) {
            // users.get(guestName).putEvent(event);
            db.getUser(guestName).putEvent(event);
            // Si hay algun invitado activo le envio un mensaje
            if (socketWriter.containsKey(guestName)) {
                // Se puede enviar el evento y que el cliente lo actualice
                socketWriter.get(guestName).writeObject("Invitation");
                socketWriter.get(guestName).writeObject(event);
                /*
                 * // Tambien se puede enviar el usuario completo actualizado y el cliente
                 * simplemente lo copia socketWriter.get(name).writeObject("Invitation");
                 * socketWriter.get(name).writeObject(user);
                 */
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // --------------------------- GETTERS, SETTERS AND CLEARS
    // ---------------------------
    // -----------------------------------------------------------------------------------

}
