package dev.lorenzzz.smplibrary.util;

/** The ASCII banner printed to the console on load, enable and disable. */
public final class Banner {

    private static final String LOGO = """
            &r
            &b░██████╗███╗░░░███╗██████╗░██╗░░░░░██╗██████╗░
            &b██╔════╝████╗░████║██╔══██╗██║░░░░░██║██╔══██╗
            &b╚█████╗░██╔████╔██║██████╔╝██║░░░░░██║██████╦╝
            &b░╚═══██╗██║╚██╔╝██║██╔═══╝░██║░░░░░██║██╔══██╗
            &b██████╔╝██║░╚═╝░██║██║░░░░░███████╗██║██████╦╝
            &b╚═════╝░╚═╝░░░░░╚═╝╚═╝░░░░░╚══════╝╚═╝╚═════╝░
            &r""";

    private static final String LINE = "&7----------------------------------------";

    private Banner() {}

    public static void loading() {
        Log.console(LOGO + """

                  &7This Plugin is Developed by &4Lorenz-Development

                  &7Every project with this Development is Reserved and has a Copyright, if you want to use this Project, contact me on discord: &4vanixy
                  &4&lWARNING: &fIf you use this Plugin without Permission, you will be reported to Authorites and your data will be used to track you down and prosecute you to the fullest extent of the law!

                                &4&lPLUGIN IS CURRENTLY LOADING, PLEASE WAIT...
                """);
    }

    public static void enabled() {
        Log.console(LINE);
        Log.console(status("&aEnabled"));
    }

    public static void disabled() {
        Log.console(status("&cDisabled"));
    }

    private static String status(String state) {
        return LOGO + """

                &bThis plugin is running on &aSMP-Library &bby &aLorenzzz&b.
                &r
                &fStatus: """ + state + "\n";
    }
}
