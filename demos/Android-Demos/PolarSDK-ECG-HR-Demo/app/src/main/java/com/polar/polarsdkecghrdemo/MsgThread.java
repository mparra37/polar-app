package com.polar.polarsdkecghrdemo;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public class MsgThread implements Runnable {
    private volatile String msg = "";
    Socket socket;
    DataOutputStream dos;
    //private PrintWriter out;

    @Override
    public void run(){
        try {
            socket = new Socket("158.97.91.129", 5678);
            //showToast("llego 1");
            //out = new PrintWriter(socket.getOutputStream(), true);
            dos = new DataOutputStream(socket.getOutputStream());
            dos.writeUTF(msg);
            //showToast("llego 2");
            dos.close();
            dos.flush();
            socket.close();
        } catch (IOException e) {
            //showToast("error");
            e.printStackTrace();
        }
    }

    public void sendMsg(String msg){
        this.msg = msg;
        run();
    }
}
