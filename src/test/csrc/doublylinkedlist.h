/* doublylinkedlist.h */
#ifndef __DOUBLYLINKEDLIST_H
#define __DOUBLYLINKEDLIST_H

#define MULTIDEST 20

#include <stdint.h>
typedef struct node *cfglink;
struct node {
	uint64_t src;
    uint64_t dest[MULTIDEST];
	int destNum;
    int label;
	cfglink prev, next;
};

void init_link(void);
cfglink make_node(uint64_t src, uint64_t dest);
void free_node(cfglink p);
cfglink search(uint64_t src);
void addDest(cfglink p, uint64_t dest);
void insert(cfglink p);
// void delete(cfglink p);
void traverse();
void destroy(void);
void enqueue(cfglink p);
cfglink dequeue(void);

#endif