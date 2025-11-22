import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class User {
    private UUID id;
    private String nickname;
    private List<UUID> accountIds;

    public User(String nickname) {
        this.id = UUID.randomUUID();
        this.nickname = nickname;
        this.accountIds = new ArrayList<>();
    }

    public void addAccount(UUID accountId) { this.accountIds.add(accountId); }
    public UUID getId() { return id; }
    public String getNickname() { return nickname; }
    public List<UUID> getAccountIds() { return new ArrayList<>(accountIds); }

    @Override
    public String toString() { return nickname; }
}

class Account {
    private UUID id;
    private UUID userId;
    private BigDecimal balance;
    private boolean isFrozen;
    private final Lock lock = new ReentrantLock();

    public Account(UUID userId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.balance = BigDecimal.ZERO;
        this.isFrozen = false;
    }

    public UUID getId() { return id; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public boolean isFrozen() { return isFrozen; }
    public void setFrozen(boolean frozen) { isFrozen = frozen; }
    public Lock getLock() { return lock; }

    @Override
    public String toString() {
        String status = isFrozen ? "❄️ ЗАМОРОЖЕН" : "✅ АКТИВЕН";
        return String.format("%s | %.2f $ | %s... | %s", status, balance, id.toString().substring(0, 8), id);
    }
}

enum ActionType {
    DEPOSIT("Пополнение"),
    WITHDRAW("Снятие"),
    FREEZE("Заморозка"),
    TRANSFER("Перевод");

    private final String label;

    ActionType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

class Transaction {
    private UUID id;
    private ActionType action;
    private BigDecimal amount;
    private UUID sourceAccountId;
    private UUID targetAccountId;

    public Transaction(ActionType action, BigDecimal amount, UUID sourceAccountId, UUID targetAccountId) {
        this.id = UUID.randomUUID();
        this.action = action;
        this.amount = amount;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
    }

    public void accept(TransactionVisitor visitor) { visitor.visit(this); }

    public UUID getId() { return id; }
    public ActionType getAction() { return action; }
    public BigDecimal getAmount() { return amount; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getTargetAccountId() { return targetAccountId; }
}

enum BankDatabase {
    INSTANCE;
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();

    public void saveUser(User user) { users.put(user.getId(), user); }
    public void saveAccount(Account account) { accounts.put(account.getId(), account); }
    public Account getAccount(UUID id) { return accounts.get(id); }
    public Collection<User> getAllUsers() { return users.values(); }
    public Collection<Account> getAllAccounts() { return accounts.values(); }
}

interface TransactionStrategy {
    void execute(Account account, BigDecimal amount) throws Exception;
}

class DepositStrategy implements TransactionStrategy {
    @Override
    public void execute(Account account, BigDecimal amount) throws Exception {
        if (account.isFrozen()) throw new IllegalStateException("Account is frozen");
        account.setBalance(account.getBalance().add(amount));
    }
}

class WithdrawStrategy implements TransactionStrategy {
    @Override
    public void execute(Account account, BigDecimal amount) throws Exception {
        if (account.isFrozen()) throw new IllegalStateException("Account is frozen");
        if (account.getBalance().compareTo(amount) < 0) throw new IllegalArgumentException("Insufficient funds");
        account.setBalance(account.getBalance().subtract(amount));
    }
}

class FreezeStrategy implements TransactionStrategy {
    @Override
    public void execute(Account account, BigDecimal amount) {
        account.setFrozen(true);
    }
}

class StrategyFactory {
    public static TransactionStrategy getStrategy(ActionType type) {
        switch (type) {
            case DEPOSIT: return new DepositStrategy();
            case WITHDRAW: return new WithdrawStrategy();
            case FREEZE: return new FreezeStrategy();
            default: throw new IllegalArgumentException("No strategy");
        }
    }
}

interface TransactionVisitor { void visit(Transaction transaction); }
class AuditVisitor implements TransactionVisitor {
    @Override
    public void visit(Transaction transaction) {
    }
}

interface TransactionObserver {
    void onTransactionCompleted(Transaction tx, boolean success, String message);
}

interface Command { void execute(); }

class TransactionCommand implements Command {
    private final Transaction transaction;
    private final List<TransactionObserver> observers;

    public TransactionCommand(Transaction transaction, List<TransactionObserver> observers) {
        this.transaction = transaction;
        this.observers = observers;
    }

    @Override
    public void execute() {
        try {
            Thread.sleep(500);

            if (transaction.getAction() == ActionType.TRANSFER) {
                handleTransfer();
            } else {
                handleSingle();
            }
            notifyObservers(true, "Success");
            transaction.accept(new AuditVisitor());
        } catch (Exception e) {
            notifyObservers(false, e.getMessage());
        }
    }

    private void handleSingle() throws Exception {
        Account account = BankDatabase.INSTANCE.getAccount(transaction.getSourceAccountId());
        if (account == null) throw new IllegalArgumentException("Account not found");

        account.getLock().lock();
        try {
            StrategyFactory.getStrategy(transaction.getAction())
                    .execute(account, transaction.getAmount());
        } finally {
            account.getLock().unlock();
        }
    }

    private void handleTransfer() throws Exception {
        Account src = BankDatabase.INSTANCE.getAccount(transaction.getSourceAccountId());
        Account dst = BankDatabase.INSTANCE.getAccount(transaction.getTargetAccountId());

        if (src == null || dst == null) throw new IllegalArgumentException("Account not found");

        Account first = src.getId().compareTo(dst.getId()) < 0 ? src : dst;
        Account second = first == src ? dst : src;

        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                new WithdrawStrategy().execute(src, transaction.getAmount());
                new DepositStrategy().execute(dst, transaction.getAmount());
            } finally {
                second.getLock().unlock();
            }
        } finally {
            first.getLock().unlock();
        }
    }

    private void notifyObservers(boolean success, String msg) {
        for (TransactionObserver obs : observers) obs.onTransactionCompleted(transaction, success, msg);
    }
}

class TransactionManager {
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final List<TransactionObserver> observers = new ArrayList<>();

    public void addObserver(TransactionObserver observer) { observers.add(observer); }

    public void processTransaction(Transaction tx) {
        executor.submit(() -> new TransactionCommand(tx, observers).execute());
    }
}

class BankFrame extends JFrame implements TransactionObserver {
    private TransactionManager txManager;

    private final Color PRIMARY_COLOR = new Color(41, 128, 185);
    private final Color SECONDARY_COLOR = new Color(52, 73, 94);
    private final Color BACKGROUND_COLOR = new Color(236, 240, 241);
    private final Color TEXT_COLOR = new Color(44, 62, 80);
    private final Color SUCCESS_COLOR = new Color(46, 204, 113);
    private final Color ERROR_COLOR = new Color(231, 76, 60);
    private final Font MAIN_FONT = new Font("Segoe UI", Font.PLAIN, 14);
    private final Font BOLD_FONT = new Font("Segoe UI", Font.BOLD, 14);

    private JComboBox<User> userCombo;
    private JComboBox<Account> accountCombo;
    private JComboBox<ActionType> actionCombo;
    private JTextField amountField;
    private JComboBox<Account> targetAccountCombo;
    private JTextArea logArea;
    private JButton executeButton;

    public BankFrame() {
        initSystem();
        initUI();
    }

    private void initSystem() {
        txManager = new TransactionManager();
        txManager.addObserver(this);

        User u1 = new User("Vanya");
        User u2 = new User("van_rebuild");

        Account a1 = new Account(u1.getId()); a1.setBalance(new BigDecimal("1000"));
        u1.addAccount(a1.getId());

        Account a2 = new Account(u2.getId()); a2.setBalance(new BigDecimal("500"));
        u2.addAccount(a2.getId());

        BankDatabase.INSTANCE.saveUser(u1);
        BankDatabase.INSTANCE.saveUser(u2);
        BankDatabase.INSTANCE.saveAccount(a1);
        BankDatabase.INSTANCE.saveAccount(a2);
    }

    private void initUI() {
        setTitle("Java Bank System");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        JPanel mainPanel = new JPanel(new BorderLayout(20, 20)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                int w = getWidth();
                int h = getHeight();
                Color color1 = BACKGROUND_COLOR;
                Color color2 = new Color(220, 230, 235);
                GradientPaint gp = new GradientPaint(0, 0, color1, 0, h, color2);
                g2d.setPaint(gp);
                g2d.fillRect(0, 0, w, h);
            }
        };
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        add(mainPanel);

        JPanel topPanel = createRoundedPanel();
        topPanel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        userCombo = createStyledComboBox(BankDatabase.INSTANCE.getAllUsers().toArray(new User[0]));
        userCombo.addActionListener(e -> updateAccountCombo());

        accountCombo = createStyledComboBox();
        updateAccountCombo();

        addComponent(topPanel, createStyledLabel("👤 Пользователь:"), 0, 0, gbc);
        addComponent(topPanel, userCombo, 1, 0, gbc);
        addComponent(topPanel, createStyledLabel("💳 Счет:"), 0, 1, gbc);
        addComponent(topPanel, accountCombo, 1, 1, gbc);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = createRoundedPanel();
        centerPanel.setLayout(new GridBagLayout());
        GridBagConstraints centerGbc = new GridBagConstraints();
        centerGbc.insets = new Insets(10, 10, 10, 10);
        centerGbc.fill = GridBagConstraints.HORIZONTAL;

        actionCombo = createStyledComboBox(ActionType.values());
        actionCombo.addActionListener(e -> toggleFields());

        amountField = createStyledTextField();
        targetAccountCombo = createStyledComboBox(BankDatabase.INSTANCE.getAllAccounts().toArray(new Account[0]));
        targetAccountCombo.setEnabled(false);

        executeButton = createStyledButton("ВЫПОЛНИТЬ ОПЕРАЦИЮ", PRIMARY_COLOR);
        executeButton.addActionListener(e -> submitTransaction());

        addComponent(centerPanel, createStyledLabel("⚙️ Тип операции:"), 0, 0, centerGbc);
        addComponent(centerPanel, actionCombo, 1, 0, centerGbc);
        addComponent(centerPanel, createStyledLabel("💰 Сумма:"), 0, 1, centerGbc);
        addComponent(centerPanel, amountField, 1, 1, centerGbc);
        addComponent(centerPanel, createStyledLabel("➡️ Счет получателя:"), 0, 2, centerGbc);
        addComponent(centerPanel, targetAccountCombo, 1, 2, centerGbc);

        centerGbc.gridwidth = 2;
        centerGbc.gridx = 0;
        centerGbc.gridy = 3;
        centerGbc.insets = new Insets(20, 10, 10, 10);
        centerPanel.add(executeButton, centerGbc);

        mainPanel.add(centerPanel, BorderLayout.CENTER);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(SECONDARY_COLOR);
        logArea.setForeground(SUCCESS_COLOR);
        logArea.setBorder(new EmptyBorder(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(800, 250));
        scrollPane.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(SECONDARY_COLOR, 2),
                "📝 Системный журнал",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                BOLD_FONT,
                SECONDARY_COLOR
        ));
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);

        mainPanel.add(scrollPane, BorderLayout.SOUTH);

        log("🚀 Система запущена. Ожидание действий...");
    }

    private void addComponent(JPanel panel, JComponent comp, int x, int y, GridBagConstraints gbc) {
        gbc.gridx = x;
        gbc.gridy = y;
        panel.add(comp, gbc);
    }

    private JLabel createStyledLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(BOLD_FONT);
        label.setForeground(TEXT_COLOR);
        return label;
    }

    private JTextField createStyledTextField() {
        JTextField field = new JTextField(15);
        field.setFont(MAIN_FONT);
        field.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(SECONDARY_COLOR, 1, true),
                new EmptyBorder(5, 10, 5, 10)
        ));
        return field;
    }

    private <T> JComboBox<T> createStyledComboBox(T[] items) {
        JComboBox<T> comboBox = new JComboBox<>(items);
        styleComboBox(comboBox);
        return comboBox;
    }

    private <T> JComboBox<T> createStyledComboBox() {
        JComboBox<T> comboBox = new JComboBox<>();
        styleComboBox(comboBox);
        return comboBox;
    }

    private <T> void styleComboBox(JComboBox<T> comboBox) {
        comboBox.setFont(MAIN_FONT);
        comboBox.setBackground(Color.WHITE);
        comboBox.setForeground(TEXT_COLOR);
        ((JComponent) comboBox.getRenderer()).setBorder(new EmptyBorder(5, 10, 5, 10));
    }

    private JButton createStyledButton(String text, Color bgColor) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                if (getModel().isArmed()) {
                    g.setColor(bgColor.darker());
                } else {
                    g.setColor(bgColor);
                }
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                super.paintComponent(g);
            }
        };
        button.setFont(BOLD_FONT);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(200, 45));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(bgColor.brighter());
            }
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(bgColor);
            }
        });
        return button;
    }

    private JPanel createRoundedPanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Dimension arcs = new Dimension(15, 15);
                int width = getWidth();
                int height = getHeight();
                Graphics2D graphics = (Graphics2D) g;
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                graphics.setColor(Color.WHITE);
                graphics.fillRoundRect(0, 0, width - 1, height - 1, arcs.width, arcs.height);
                graphics.setColor(SECONDARY_COLOR);
                graphics.drawRoundRect(0, 0, width - 1, height - 1, arcs.width, arcs.height);
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    private void updateAccountCombo() {
        User selectedUser = (User) userCombo.getSelectedItem();
        accountCombo.removeAllItems();
        if (selectedUser != null) {
            for (UUID accId : selectedUser.getAccountIds()) {
                accountCombo.addItem(BankDatabase.INSTANCE.getAccount(accId));
            }
        }
    }

    private void toggleFields() {
        ActionType type = (ActionType) actionCombo.getSelectedItem();
        boolean isTransfer = type == ActionType.TRANSFER;
        boolean isFreeze = type == ActionType.FREEZE;

        targetAccountCombo.setEnabled(isTransfer);
        amountField.setEnabled(!isFreeze);
        if (isFreeze) amountField.setText("0.00");

        if (isFreeze) {
            executeButton.setBackground(ERROR_COLOR);
            executeButton.setText("ЗАМОРОЗИТЬ СЧЕТ");
        } else if (isTransfer) {
            executeButton.setBackground(PRIMARY_COLOR);
            executeButton.setText("ПЕРЕВЕСТИ СРЕДСТВА");
        } else {
            executeButton.setBackground(SUCCESS_COLOR);
            executeButton.setText("ВЫПОЛНИТЬ ОПЕРАЦИЮ");
        }
    }

    private void submitTransaction() {
        try {
            Account source = (Account) accountCombo.getSelectedItem();
            if (source == null) {
                JOptionPane.showMessageDialog(this, "Счет не выбран!", "Ошибка", JOptionPane.ERROR_MESSAGE);
                return;
            }

            ActionType type = (ActionType) actionCombo.getSelectedItem();
            BigDecimal amount = new BigDecimal("0");
            if (type != ActionType.FREEZE) {
                String amtText = amountField.getText().replace(",", ".");
                if (amtText.isEmpty()) throw new NumberFormatException();
                amount = new BigDecimal(amtText);
            }

            UUID targetId = null;
            if (type == ActionType.TRANSFER) {
                Account target = (Account) targetAccountCombo.getSelectedItem();
                if (target == null || target.getId().equals(source.getId())) {
                    JOptionPane.showMessageDialog(this, "Некорректный счет получателя!", "Ошибка", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                targetId = target.getId();
            }

            Transaction tx = new Transaction(type, amount, source.getId(), targetId);

            log("⏳ Запрос: " + type + " | Сумма: " + amount + " $");
            executeButton.setEnabled(false);
            executeButton.setText("ОБРАБОТКА...");

            txManager.processTransaction(tx);

        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Неверный формат суммы!", "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void log(String msg) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        logArea.append("[" + time + "] " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    @Override
    public void onTransactionCompleted(Transaction tx, boolean success, String message) {
        SwingUtilities.invokeLater(() -> {
            String status = success ? "✅ УСПЕШНО" : "❌ ОШИБКА (" + message + ")";
            log("Транзакция завершена: " + status);

            toggleFields();
            executeButton.setEnabled(true);

            accountCombo.repaint();
            targetAccountCombo.repaint();

            if (accountCombo.getSelectedItem() != null) {
                int idx = accountCombo.getSelectedIndex();
                accountCombo.setSelectedIndex(-1);
                accountCombo.setSelectedIndex(idx);
            }
            amountField.setText("");
        });
    }
}

public class BankSystem {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                    if ("Nimbus".equals(info.getName())) {
                        UIManager.setLookAndFeel(info.getClassName());
                        break;
                    }
                }
                if (!UIManager.getLookAndFeel().getName().equals("Nimbus")) {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
            new BankFrame().setVisible(true);
        });
    }
}