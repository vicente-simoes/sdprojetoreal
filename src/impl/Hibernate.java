package impl;

import api.java.Result;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.hibernate.exception.LockAcquisitionException;

import java.io.File;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * A helper class to perform POJO (Plain Old Java Objects) persistence, using Hibernate and a backing relational database.
 */
public class Hibernate {
	private static final String HIBERNATE_CFG_FILE = "hibernate.cfg.xml";
	private SessionFactory sessionFactory;
	private static Hibernate instance;

	private static final Logger log = Logger.getLogger(Hibernate.class.getName());

	private Hibernate() {
		try {
			sessionFactory = new Configuration()
            .configure(new File(HIBERNATE_CFG_FILE))
            .buildSessionFactory();

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns the Hibernate instance, initializing if necessary.
	 * Requires a configuration file (hibernate.cfg.xml)
	 * @return
	 */
	synchronized public static Hibernate getInstance() {
		if (instance == null)
			instance = new Hibernate();
		return instance;
	}
	
	/**
	 * Persists one or more objects to storage
	 * @param objects - the objects to persist
	 */ 
	public void persist(Object... objects) {
		Transaction tx = null;
		try(var session = sessionFactory.openSession()) {
		     tx = session.beginTransaction();
		     for( var o : objects )
		    	 session.persist(o);
		     tx.commit();			
		} catch (Exception e) {
		     if (tx!=null) tx.rollback();
		     throw e;
		}
	}

	/**
	 * Gets one object from storage
	 * @param identifier - the objects identifier
	 * @param clazz - the class of the object that to be returned
	 */ 
	public <T> T get(Class<T> clazz, Object identifier) {
		Transaction tx = null;
		T element = null;
		try(var session = sessionFactory.openSession()) {
		     tx = session.beginTransaction();
		     element = session.get(clazz, identifier);
		     tx.commit();			
		} catch (Exception e) {
		     if (tx!=null) tx.rollback();
		     throw e;
		}
		return element;
	}
	
	/**
	 * Updates one or more objects previously persisted.
	 * @param objects - the objects to update
	 */
	public void update(Object... objects) {
		Transaction tx = null;
		try(var session = sessionFactory.openSession()) {
		     tx = session.beginTransaction();
		     for( var o : objects )
		    	 session.merge(o);
		     tx.commit();			
		} catch (Exception e) {
		     if (tx!=null) tx.rollback();
		     throw e;
		}
	}
	
	/**
	 * Removes one or more objects from storage 
	 * @param objects - the objects to remove from storage
	 */
	public void delete(Object... objects) {
		Transaction tx = null;
		try(var session = sessionFactory.openSession()) {
		     tx = session.beginTransaction();
		     for( var o : objects )
		    	 session.remove(o);
		     tx.commit();			
		} catch (Exception e) {
		     if (tx!=null) tx.rollback();
		     throw e;
		}
	}
	
	/**
	 * Performs a jpql Hibernate query (SQL dialect) 
	 * @param <T> The type of objects returned by the query
	 * @param jpqlStatement - the jpql query statement
	 * @param clazz - the class of the objects that will be returned
	 * @return - list of objects that match the query
	 */
	public <T> List<T> jpql(String jpqlStatement, Class<T> clazz) {
		try(var session = sessionFactory.openSession()) {
			var query = session.createQuery(jpqlStatement, clazz);
        	return query.list();
		} catch (Exception e) {
		    throw e;
		}
	}

	/**
	 * Performs a (native) SQL query  
	 * 
	 * @param <T> The type of objects returned by the query
	 * @param sqlStatement - the sql query statement
	 * @param clazz - the class of the objects that will be returned
	 * @return - list of objects that match the query
	 */
	public <T> List<T> sql(String sqlStatement, Class<T> clazz) {
		try(var session = sessionFactory.openSession()) {
			var query = session.createNativeQuery(sqlStatement, clazz);
        	return query.list();
		} catch (Exception e) {
		    throw e;
		}
	}

	public <T> Result<T> execTransaction(Function<Session, Result<T>> f) {
		Transaction tx = null;
		var maxRetries = 5;
		for (int i = 0; i < maxRetries; i++) {
			try(var session = sessionFactory.openSession()) {
				tx = session.beginTransaction();
				var val = f.apply(session);
				if (val.isOK())
					tx.commit();
				else
					tx.rollback();
				return val;
			} catch (Exception e) {
				var sleepTime = 20L;
				log.warning(e.getMessage() + "\n");
				if (tx != null) {
					tx.rollback();
					log.warning("Retrying operation after %d milliseconds\n".formatted(sleepTime));
					try {
						Thread.sleep(sleepTime);
					} catch (InterruptedException ignored) {}
				}
			}
		}
		return Result.error(Result.ErrorCode.INTERNAL_ERROR);
	}

	//Deletes all stored data in between tests
	public static void teardown() {
		instance = new Hibernate();
	}
	
}