/* doublylinkedlist.c */
#include <stdlib.h>
#include "doublylinkedlist.h"
#include <stdio.h>

struct node tailsentinel;
struct node headsentinel;
// struct node headsentinel = {0, {0}, 0, NULL, &tailsentinel};
// struct node tailsentinel = {0, {0}, 0, &headsentinel, NULL};

static cfglink head = &headsentinel;
static cfglink tail = &tailsentinel;

void init_link(void) {
	// headsentinel = {0, {0}, 0, NULL, &tailsentinel};
	headsentinel.src = 0;
	headsentinel.destNum = 0;
	headsentinel.label = 0;
	for(int i = 0; i < MULTIDEST; i++) {
		headsentinel.dest[i] = 0;
	}
	headsentinel.prev = NULL;
	headsentinel.next = &tailsentinel;
	// tailsentinel = {0, {0}, 0, &headsentinel, NULL}
	tailsentinel.src = 0;
	tailsentinel.destNum = 0;
	tailsentinel.label = 0;
	for(int i = 0; i < MULTIDEST; i++) {
		tailsentinel.dest[i] = 0;
	}
	tailsentinel.prev = &headsentinel;
	tailsentinel.next = NULL;
}

cfglink make_node(uint64_t src, uint64_t dest)
{
	cfglink p;
	p = (cfglink)malloc(sizeof *p);
	p->src = src;
	p->dest[0] = dest;
	p->destNum = 1;
	for(int i = 1; i < MULTIDEST; i++) {
		p->dest[i] = 0;
	}
	p->prev = p->next = NULL;
	return p;
}

void free_node(cfglink p)
{
	free(p);
}

cfglink search(uint64_t src)
{
	cfglink p;
	for (p = head->next; p != tail; p = p->next)
		if (p->src == src)
			return p;
	return NULL;
}

void addDest(cfglink p, uint64_t dest) {
	for(int i = 0; i < MULTIDEST; i++) {
		if(dest == p->dest[i]) {
			break;
		}
		if(p->dest[i] == 0) {
			p->dest[i] = dest;
			p->destNum = i+1;
			break;
		}
	}
}

void insert(cfglink p)
{
	p->next = head->next;
	head->next->prev = p;
	head->next = p;
	p->prev = head;
	p->label = p->next->label + 1;
}

// void delete(cfglink p)
// {
// 	p->prev->next = p->next;
// 	p->next->prev = p->prev;
// }

void traverse()
{
	cfglink p;

	if(head->next->src == 0) {
		return;
	}
	// for (p = head->next; p != tail; p = p->next) {
	// 	printf("src : %lx, destnum : %d, dest : ", p->src, p->destNum);
	// 	for(int i = 0; i < MULTIDEST; i++) {
	// 		if(p->dest[i] != 0) {
	// 			printf("%lx ", p->dest[i]);
	// 		}
	// 	}
	// 	printf("\n");
	// }
	for (p = tail->prev; p != head; p = p->prev) {
		printf("src : %lx, label : %d, destnum : %d, dest : ", p->src, p->label, p->destNum);
		for(int i = 0; i < MULTIDEST; i++) {
			if(p->dest[i] != 0) {
				printf("%lx ", p->dest[i]);
			}
		}
		printf("\n");
	}
}

void destroy(void)
{
	cfglink q, p = head->next;
	head->next = tail;
	tail->prev = head;
	while (p != tail) {
		q = p;
		p = p->next;
		free_node(q);
	}
}

void enqueue(cfglink p)
{
	insert(p);
}

cfglink dequeue(void)
{
	if (tail->prev == head)
		return NULL;
	else {
		cfglink p = tail->prev;
		delete(p);
		return p;
	}
}