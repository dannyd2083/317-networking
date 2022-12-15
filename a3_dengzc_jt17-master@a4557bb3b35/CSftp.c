#include <sys/types.h>
#include <sys/socket.h>
#include <stdio.h>
#include "dir.h"
#include "usage.h"
#include <pthread.h>
#include <netinet/in.h>
#include <errno.h>
#include <netdb.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <arpa/inet.h>
#include <sys/wait.h>
#include <signal.h>
#include <regex.h>
#include<ifaddrs.h>

// Here is an example of how to use the above function. It also shows
// one how to get the arguments passed on the command line.
/* this function is run by the second thread */


#define BACKLOG 10 // how many pending connections queue will hold
#define BUFFER_SIZE 1024


 void sigchld_handler(int s){
  // waitpid() might overwrite errno, so we save and restore it:
  int saved_errno = errno;
  while(waitpid(-1, NULL, WNOHANG) > 0);
  errno = saved_errno;
  }

 // get sockaddr, IPv4 or IPv6:
 void *get_in_addr(struct sockaddr *sa){
  if (sa->sa_family == AF_INET) {
    return &(((struct sockaddr_in*)sa)->sin_addr);
    }
    return &(((struct sockaddr_in6*)sa)->sin6_addr);
}

void *inc_x()
{
  printf("x increment finished\n");
  return NULL;
}


void write_message (int client, int state, char *str){
  char buf[BUFFER_SIZE];
  int message;
  switch(state) {
    case 150:
      message = snprintf(buf, BUFFER_SIZE, "150 File status okay; about to open data connection. %s\r\n", str);
      break;
    case 200:
      message = snprintf(buf, BUFFER_SIZE, "200 Command okay. %s\r\n", str);
      break;
    case 220:
      message = snprintf(buf, BUFFER_SIZE, "220 Service ready for new user. %s\r\n", str);
      break;  
    case 221:
      message = snprintf(buf, BUFFER_SIZE, "221 Service closing control connection. %s\r\n", str);
      break;
    case 226:
      message = snprintf(buf, BUFFER_SIZE, "226 Closing data connection. %s\r\n", str);
      break;
    case 230:
      message = snprintf(buf, BUFFER_SIZE, "230 User logged in, proceed. %s\r\n", str);
      break;
    case 250: //not in 2lao's code
      message = snprintf(buf, BUFFER_SIZE, "250 Requested file action okay, completed. %s\r\n", str);
      break;
    case 227: 
      message = snprintf(buf, BUFFER_SIZE, "227 Entering Passive Mode %s\r\n", str);
      break;
    case 425:
      message = snprintf(buf, BUFFER_SIZE, "425 Can't open data connection. %s\r\n", str);
      break;
    case 426: //not in 2lao's code
      message = snprintf(buf, BUFFER_SIZE, "426 Connection closed; transfer aborted. %s\r\n", str);
      break;
    case 450: //not in 2lao's code
      message = snprintf(buf, BUFFER_SIZE, "450 Requested file action not taken.File unavailable (e.g., file busy). %s\r\n", str);
      break;
    case 451: //not in 2lao's code
      message = snprintf(buf, BUFFER_SIZE, "451 Requested action aborted: local error in processing. %s\r\n", str);
      break;
    case 500:
      message = snprintf(buf, BUFFER_SIZE, "500 Syntax error, command unrecognized. %s\r\n", str);
      break;
    case 501:
      message = snprintf(buf, BUFFER_SIZE, "501 Syntax error in parameters or arguments. %s\r\n", str);
      break;
    case 504:
      message = snprintf(buf, BUFFER_SIZE, "504 Command not implemented for that parameter. %s\r\n", str);
      break;
    case 530:
      message = snprintf(buf, BUFFER_SIZE, "530 Not logged in. %s\r\n", str);
      break;
    case 550:
      message = snprintf(buf, BUFFER_SIZE, "550 Requested action not taken. File unavailable (e.g., file not found, no access). %s\r\n", str);
      break;
  }
  if (write(client, buf, message) < 0)  perror("message didn't reach");


}


void cwd_handler (int socket, char *pathname){
  regex_t regex;
  regcomp(&regex, "^(\\.\\.|\\.).*(\\.\\.)?.*",REG_EXTENDED | REG_NOSUB);
  if (regexec(&regex,pathname,0,NULL,0) == 0 )
  {
    write_message(socket,550,"");

  }else if (chdir(pathname) == 0)
  {
    write_message(socket,250,"");
  
  }else{
     write_message(socket,550,"");
  }
}

void cdup_handler (int socket, char *pathname){
  char currentPath[BUFFER_SIZE];
  memset(currentPath,0,sizeof currentPath);
  getcwd(currentPath,sizeof currentPath);
  if (strcmp (pathname,currentPath) == 0){
    write_message(socket,550,currentPath);
  } else {
    if(chdir(".." )== 0){
      write_message(socket,200,currentPath);
    } else{
      write_message(socket,500,currentPath);
    }
  }
}

void type_handler (int socket, char* argument){

   //only support the Image and ASCII type (3.1.1, 3.1.1.3)
  if(strcasecmp(argument,"A") == 0 || strcasecmp(argument,"I") == 0)
  {
    write_message(socket,200,"");
  }
  else if (strcasecmp(argument,"E") == 0 || strcasecmp(argument,"L") == 0)
  {
    write_message(socket,504,"");
  }
  else{
    write_message(socket,501,"");
  }
}

void mode_handler(int socket , char* argument){
  if (strcasecmp(argument, "S") == 0){
    write_message(socket,200, "");
  }else if (strcasecmp(argument, "B") == 0 || strcasecmp(argument, "C") == 0)
  {
    write_message(socket,504,"");
  }else{
    write_message(socket, 501, "");
  }
  
}

void stru_handler(int socket , char* argument){
  if (strcasecmp(argument, "F") == 0)
  {
    write_message(socket, 200, "");
  }
  else if (strcasecmp(argument, "P") == 0 || strcasecmp(argument, "R") == 0)
  {
    write_message(socket, 504, "");
  }
  else
  {
    write_message(socket, 501, "");
  }
}

int accept_new_socket (int passive_socket) {
  /* code referred from 
  https://man7.org/linux/man-pages/man2/select.2.html
  https://stackoverflow.com/questions/10248380/checking-for-errors-before-recv-called
  */
  fd_set rfds;
  struct timeval tv;
  int retval;

  /* Watch stdin (fd 0) to see when it has input. */
  FD_ZERO(&rfds);
  FD_SET(passive_socket, &rfds);

  /* Wait up to 30 seconds. */
  tv.tv_sec = 30;
  tv.tv_usec = 0;

  int err_code;
  socklen_t len = sizeof(err_code);

  retval = select(passive_socket+1, &rfds, NULL, NULL, &tv);
  /* Don't rely on the value of tv now! */
  if (retval == -1)
    return -1;
  else if (retval) {
    if (getsockopt(passive_socket, SOL_SOCKET, SO_ERROR, &err_code, &len) != 0) return -1;
    if (err_code) return -1;
    /* printf("Data is available now.\n");
    FD_ISSET(0, &rfds) will be true. */
    struct sockaddr_in new_client;
    socklen_t new_client_len = sizeof(struct sockaddr_in);
    int result = accept(passive_socket, (struct sockaddr *)&new_client, &new_client_len);
    return result;
  } else
    printf("No data within 30 seconds.\n");
  return -1;
}

void retr_handler(int socket , char* argument, int *client_data, int *passive_socket, int *is_passive) {
  if (*is_passive)
  {
    *client_data = accept_new_socket(*passive_socket);
  }
  else
  {
    write_message(socket, 425, "");
    return;
  }

  if (*client_data < 0)
  {
    write_message(socket, 425, "");
    return;
  }

  write_message(socket, 150, "");

  FILE *file = NULL;
  file = fopen(argument, "r");
  int ret = 1;
  if (file)
  {
    fseek(file, 0, SEEK_SET);
    char file_buffer[BUFFER_SIZE + 1];
    int ret = 0;
    int n = fread(file_buffer, 1, BUFFER_SIZE, file);
    int st = write(*client_data, file_buffer, n);
    if (st < 0) ret = -1;
    if (n > st) ret = -2;
  }
  else
  {
    ret = -1;
  }
  ret = fclose(file);
  if (ret == -1)
  {
    write_message(socket, 426, "");
  }
  else if (ret == -2)
  {
    write_message(socket, 426, "The short writes happend.");
  }
  else
  {
    write_message(socket, 226, "");
  }
}




int new_passive_socket (int port) {

  int passive_socket = socket(PF_INET,SOCK_STREAM,0); 
  struct ifaddrs *ifap;
  struct ifaddrs *ifa;
  struct sockaddr_in *sa;

  //get avaliable IP addresses
  //https://stackoverflow.com/questions/4139405/how-can-i-get-to-know-the-ip-address-for-interfaces-in-c
  getifaddrs (&ifap);
  for (ifa = ifap; ifa; ifa = ifa->ifa_next) {
      if (ifa->ifa_addr && ifa->ifa_addr->sa_family==AF_INET) {
          sa = (struct sockaddr_in *) ifa->ifa_addr;
          break;
      }
  }
  freeifaddrs(ifap);

  int yes = 1;
  if (setsockopt(passive_socket, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(int)) != 0) {
      perror("Fail to set PASV socket's option");
      return -1;
  }
  //bind
  struct sockaddr_in new_addr;
  memset(&new_addr,0,sizeof new_addr);
  // memset(new_addr.sin_zero,0,sizeof(new_addr.sin_zero));
  new_addr.sin_family = AF_INET;
  new_addr.sin_port = htons(port); // short, network byte order
  new_addr.sin_addr.s_addr = sa->sin_addr.s_addr;
  // new_addr.sin_addr.s_addr = INADDR_ANY;

  if (bind(passive_socket, (struct sockaddr *)&new_addr, sizeof(new_addr)) < 0)
  {
    return -1;
  }

  if (listen(passive_socket, 1) < 0)
  {
    return -1;
  }

  return passive_socket;
}


void pasv_handler(int client_socket , char* argument,  int *passive_socket , int * is_passive) {

  if (*passive_socket >= 0)
  {
    close(*passive_socket);
  }


  // Set up a random port number for data connection.
  // rand() and % func applied from https://stackoverflow.com/questions/19553265/how-does-modulus-and-rand-work
  // port number range referenced from https://www.ibm.com/docs/en/ztpf/2020?topic=overview-port-numbers
  int port = (rand() % (65535 - 1024) + 1024);

  *passive_socket =  new_passive_socket(port);


  if (*passive_socket < 0)
  {
    write_message(client_socket, 500, "");
  }
  else {
    *is_passive = 1; 
    socklen_t size = sizeof (struct sockaddr_in);
    struct sockaddr_in addr;
    getsockname(*passive_socket, (struct sockaddr *) &addr, &size);
    char *hostAddress = inet_ntoa(addr.sin_addr);
    int ip[4];
    sscanf(hostAddress, "%d.%d.%d.%d", &ip[0], &ip[1], &ip[2], &ip[3]);
    char str[80];
    sprintf(str, "(%d,%d,%d,%d,%d,%d)",ip[0],ip[1],ip[2],ip[3],port/256,port%256);
    printf("%d,%d,%d,%d,%d,%d\n", 
            ip[0],
            ip[1],
            ip[2],
            ip[3],
            port / 256,
            port % 256);
    write_message(client_socket, 227, str);
  }
}


void nlst_handler(int client_socket, int *client_data,int *passive_socket, int *is_passive){
  if (*is_passive == 1) *client_data = accept_new_socket(*passive_socket);
  write_message (client_socket,150,"");
  int result = listFiles(*client_data,".");
   if (result == -1)
      {
        write_message(client_socket, 451, "");
      }
      else if (result == -2)
      {
        write_message(client_socket, 450, "");
      }
      else
      {
        write_message(client_socket, 226, "");
      }
      close(*client_data);
      close(*passive_socket);
}



int check_arg_count (int client_socket, int count, int expected_count){
  if (count != expected_count){
    write_message(client_socket,501,"Wrong number of arguments.");
    return -1;
  }
  return 1;
}



int parse_message (char* command, char* argument, int client_socket, int *logged_in, int *client_data, int *passive_socket, int *is_passive, int count, char* root) {
  if (command == NULL) {
    write_message(client_socket, 501, "NULL command");
    return 0;
  }
  if (argument == NULL && count != 0) {
    write_message(client_socket, 501, "NULL argument");
    return 0;
  }
  if (strcasecmp(command, "USER") == 0){
    // Handle USER command.
    if (*logged_in)
    {
      write_message(client_socket, 220, "");
      return 0;
    }
    if (count != 1)
    {
      write_message(client_socket, 501, "");
      return 0;
    }
    if (strcmp(argument, "cs317") == 0)
    {
      write_message(client_socket, 230, "");
  
      *logged_in = 1;
  
      return 0;
    }
    else
    {
      write_message(client_socket, 530, "Username is incorrect.");
      return 0;
    }
  } 
  else if (strcasecmp(command, "QUIT") == 0)
  {
    // Handle QUIT command.
    if (check_arg_count(client_socket,count, 0) == -1) return 0;
    write_message(client_socket, 221, "");
    return -1;
  } 
  else if (!*logged_in) {
    write_message(client_socket, 530, "");
    return 0;
  }
  else if (strcasecmp(command, "CWD") == 0)
  {
    // Handle CWD command
    if (check_arg_count(client_socket,count, 1) == -1) return 0;
    cwd_handler(client_socket, argument);

    return 0;
  }
  else if (strcasecmp(command, "CDUP") == 0)
  {
    //Handle CDUP
    if(check_arg_count(client_socket,count,0)== -1) return 0;
    cdup_handler(client_socket,root);
    return 0;
  }
  else if (strcasecmp(command,"TYPE") == 0)
  {
    //Handle TYPE
    if(check_arg_count(client_socket,count,1) == -1) return 0;
    type_handler(client_socket,argument);
    return 0;
  }
  else if (strcasecmp(command,"MODE") == 0)
  {
    //Handle MODE
    if(check_arg_count(client_socket,count,1) == -1) return 0;
    mode_handler(client_socket,argument);
    return 0;
  }
  else if (strcasecmp(command,"STRU") == 0)
  {
    //Handle STRU
    if(check_arg_count(client_socket,count,1) == -1) return 0;
    stru_handler(client_socket,argument);
    return 0;
  }
  else if (strcasecmp(command,"RETR") == 0)
  {
    //Handle RETR
    if(check_arg_count(client_socket,count,1) == -1) return 0;
    retr_handler(client_socket,argument, client_data, passive_socket, is_passive);
    return 0;
  }
  else if (strcasecmp(command,"PASV") == 0)
  {
    //Handle PASV
    if(check_arg_count(client_socket,count,0) == -1) return 0;
    pasv_handler(client_socket,argument, passive_socket,is_passive);
    return 0;
  }
  else if (strcasecmp(command,"NLST") == 0)
  {
    //Handle NLST
    if(check_arg_count(client_socket,count,0) == -1) return 0;
    nlst_handler(client_socket,client_data, passive_socket, is_passive);
    return 0;
  }
  else{
    write_message(client_socket,500,"");
    return 0;
  }

  return 0;
}

// remove ending space
void trim(char *buf)
{
  if (buf != NULL) {
    buf[strcspn(buf, "\n")] = 0;
    buf[strcspn(buf, "\r")] = 0;
  }
}


void *talk(void *args){

  char buf[BUFFER_SIZE];
  memset(&buf,0,sizeof buf);

  char root[BUFFER_SIZE];
  memset(&buf,0,sizeof root);
  getcwd(root,BUFFER_SIZE);
  int client_socket = *(int *)args;
  int logged_in = 0;
  int client_data = -1;
  int passive_socket = -1;
  int is_passive = -1;
  write_message(client_socket, 220, "");

  while(1) {
    memset(buf,0,BUFFER_SIZE);
    ssize_t length = read(client_socket, buf, BUFFER_SIZE);

    if (length < 0)
    {
      perror("Failed to read from the socket");
      break;
    }
    if (length == 0)
    {
      printf("EOF\n");
      break;
    }
    trim(buf);

    char *pointer = buf;
    int count = 0;
    while ((pointer = strchr(pointer, ' ')) != NULL)
    {
      pointer++;
      count++;
    }

    char *command = strtok(buf, " ");
    trim(command);

    char *argument = strtok(NULL, " ");
    trim(argument); 

    if(parse_message(command, argument, client_socket, &logged_in, &client_data, &passive_socket, &is_passive, count, root)== -1) {
      break;
    }
  }
  close(client_socket);
  return NULL;
}



int main(int argc, char **argv) {

    // This is some sample code feel free to delete it
    // This is the main program for the thread version of nc

    int i;
    pthread_t child;
    pthread_create(&child, NULL, inc_x, NULL);

    int sockfd, new_fd; // listen on sock_fd, new connection on new_fd
    struct addrinfo hints, *servinfo, *p;
    struct sockaddr_storage their_addr; // connector's address information
    socklen_t sin_size;
    struct sigaction sa;
    int yes = 1;
    char s[INET6_ADDRSTRLEN];
    int rv;


    // Check the command line arguments
    if (argc != 2) {
      usage(argv[0]);
      return -1;
    }

    memset(&hints, 0, sizeof hints);
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_PASSIVE; // use my IP


     if ((rv = getaddrinfo(NULL, argv[1], &hints, &servinfo)) != 0) {
        fprintf(stderr, "getaddrinfo: %s\n", gai_strerror(rv));
        exit(1);
    }

    // loop through all the results and bind to the first we can
    for(p = servinfo; p != NULL; p = p->ai_next) {
        if ((sockfd = socket(p->ai_family, p->ai_socktype, p->ai_protocol)) == -1) {
            perror("server: socket");
            continue;
        }

        if (setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(int)) == -1) {
            perror("setsockopt");
            exit(1);
        }

        if (bind(sockfd, p->ai_addr, p->ai_addrlen) == -1) {
            close(sockfd);
            perror("server: bind");
            continue;
        }

        break;
    }

    freeaddrinfo(servinfo); // all done with this structure

    if (p == NULL)  {
      fprintf(stderr, "server: failed to bind\n");
      exit(1);
    }

    if (listen(sockfd, BACKLOG) == -1) {
      perror("listen");
      exit(1);
    }

    sa.sa_handler = sigchld_handler; // reap all dead processes
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    if (sigaction(SIGCHLD, &sa, NULL) == -1) {
      perror("sigaction");
      exit(1);
    }

    printf("server: waiting for connections...\n");

    while(1) {  // main accept() loop
      sin_size = sizeof their_addr;
      new_fd = accept(sockfd, (struct sockaddr *)&their_addr, &sin_size);
      if (new_fd == -1) {
        perror("accept");
        continue;
      }
      
      inet_ntop(their_addr.ss_family,
        get_in_addr((struct sockaddr *)&their_addr),
        s, sizeof s);
      printf("server: got connection from %s\n", s);

      pthread_t thread;
      if (pthread_create(&thread, NULL, talk, &new_fd) != 0)
      {
        perror("Failed to create the thread");
        continue;
      } 
      pthread_join(thread, NULL);
    }

    // This is how to call the function in dir.c to get a listing of a directory.
    // It requires a file descriptor, so in your code you would pass in the file descriptor 
    // returned for the ftp server's data connection
    
    printf("Printed %d directory entries\n", listFiles(1, "."));
    return 0;

}
