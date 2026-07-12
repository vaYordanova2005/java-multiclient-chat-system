# Multi-Client Chat Application (Java)

##  Description

This project is a real-time multi-client chat system implemented in Java using sockets and multithreading. The server handles multiple clients simultaneously, allowing real-time message exchange between users.

## Features

* Client-server architecture using Java sockets
* Multi-threaded server using ClientHandler for each client
* Real-time message broadcasting between connected users
* User join/leave notifications
* Chat history persistence in a local file (`history.txt`)
* Console-based client application

## Technologies Used

* Java
* TCP Sockets
* Multithreading
* File I/O
* OOP principles

## How it works

* Server listens for incoming client connections
* Each client runs in a separate thread
* Messages are broadcast to all connected clients
* Chat history is saved and loaded from file

## Project Structure

* Server / ServerMain
* Client / ClientMain
* ClientHandler
* history.txt (message storage)

## Purpose

This project demonstrates fundamental networking and concurrency concepts in Java.
