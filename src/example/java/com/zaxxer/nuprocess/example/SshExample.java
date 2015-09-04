package com.zaxxer.nuprocess.example;

import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.zaxxer.nuprocess.NuAbstractProcessHandler;
import com.zaxxer.nuprocess.NuProcess;
import com.zaxxer.nuprocess.NuProcessBuilder;

public class SshExample
{
    private PrintWriter writer = new PrintWriter(System.out);
    private String host;

    public static void main(String[] args)
    {
        if (args.length < 1)
        {
            System.err.println("Usage: java com.zaxxer.nuprocess.example.SshExample <host>");
            System.exit(0);
        }

        SshExample ssh = new SshExample(args[0]);
        ssh.execute();
    }

    private SshExample(String host)
    {
        this.host = host;
    }

    private void execute()
    {
        NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList("ssh", host));
        ProcessHandler processHandler = new ProcessHandler();
        pb.setProcessListener(processHandler);
        NuProcess np = pb.start();

        processHandler.write("cd");
        processHandler.write("ls -l");
        processHandler.write("exit");

        processHandler.awaitDisconnection();
    }

    class ProcessHandler extends NuAbstractProcessHandler
    {
        private NuProcess nuProcess;
        private LinkedList<String> cmdList = new LinkedList<String>();
        private Semaphore disconnected = new Semaphore(0);

        @Override
        public void onStart(NuProcess nuProcess)
        {
            this.nuProcess = nuProcess;
        }

        public void awaitDisconnection()
        {
            disconnected.acquireUninterruptibly(1);
        }

        public void clear()
        {
            cmdList.clear();
        }

        public void close()
        {
            nuProcess.destroy(false);
        }

        //Send key event
        public void sendKey(String ch)
        {
            clear();
            nuProcess.writeStdin(ByteBuffer.wrap(ch.getBytes()));
        }

        //Send command
        public void write(String stack)
        {
            cmdList.add(stack + "\n");
            //nuProcess.hasPendingWrites();
            nuProcess.wantWrite();
        }

        public synchronized Boolean isPending()
        {
            return nuProcess.hasPendingWrites();
        }

        @Override
        public boolean onStdinReady(ByteBuffer buffer)
        {
            if (!cmdList.isEmpty())
            {
                String cmd = cmdList.poll();
                buffer.put(cmd.getBytes());
                buffer.flip();
            }

            return !cmdList.isEmpty();
        }

        @Override
        public void onStdout(ByteBuffer buffer, boolean closed)
        {
            int remaining = buffer.remaining();
            byte[] bytes = new byte[remaining];
            buffer.get(bytes);

            writer.print(new String(bytes));
            writer.flush();

            if (closed)
            {
                disconnected.release();
            }

            // nuProcess.wantWrite();
            // We're done, so closing STDIN will cause the "cat" process to exit
            //nuProcess.closeStdin();
        }

        @Override
        public void onStderr(ByteBuffer buffer, boolean closed)
        {
            this.onStdout(buffer, false);
        }
    }
}
