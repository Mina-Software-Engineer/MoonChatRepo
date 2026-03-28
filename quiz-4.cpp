#include <iostream>
#include <string>

using namespace std;

void printMainMenu();
void handleMainMenuInput(int input);
void printSequentialMenu();
void printIndexedMenu();
void printLinkedMenu();
void handleSubmenuInput(int input, string structureType);

int main() {
    printMainMenu();
    return 0;
}

void printMainMenu() {
    cout << "Select a file structure type:\n"
         << "1. Sequential\n"
         << "2. Indexed\n"
         << "3. Linked\n"
         << "Enter choice: ";

    int input;
    cin >> input;

    handleMainMenuInput(input);
}

void handleMainMenuInput(int input) {
    switch(input) {
        case 1:
            printSequentialMenu();
            break;
        case 2:
            printIndexedMenu();
            break;
        case 3:
            printLinkedMenu();
            break;
        default:
            cout << "Invalid input. Please try again.\n";
            printMainMenu();
            break;
    }
}

void printSequentialMenu() {
    cout << "Sequential File Structure\n"
         << "1. Add record\n"
         << "2. Delete record\n"
         << "3. Search record\n"
         << "Enter choice: ";

    handleSubmenuInput(1, "Sequential");
}

void printIndexedMenu() {
    cout << "Indexed File Structure\n"
         << "1. Add record\n"
         << "2. Delete record\n"
         << "3. Search record\n"
         << "Enter choice: ";

    handleSubmenuInput(2, "Indexed");
}

void printLinkedMenu() {
    cout << "Linked File Structure\n"
         << "1. Add record\n"
         << "2. Delete record\n"
         << "3. Search record\n"
         << "Enter choice: ";

    handleSubmenuInput(3, "Linked");
}

void handleSubmenuInput(int input, string structureType) {
    // Perform actions based on user input
    // ...

    // Return to main menu
    cout << "Returning to main menu...\n\n";
    printMainMenu();
}
